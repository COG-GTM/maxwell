package com.zendesk.maxwell.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.ref.Cleaner;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
   a wrapper class for a linked list that will keep N tail elements
   in memory, spilling its head onto disk as needed.

   The spill file is created owner-only (0600 on POSIX) and is deleted as soon
   as it has been drained, when the buffer is closed or garbage collected, or
   at JVM shutdown.
 */
public class ListWithDiskBuffer<T> implements Closeable {
	static final Logger LOGGER = LoggerFactory.getLogger(ListWithDiskBuffer.class);
	private static final Cleaner CLEANER = Cleaner.create();
	private static final Set<SpillFile> LIVE_SPILL_FILES = ConcurrentHashMap.newKeySet();

	static {
		// closes the streams before deleting, so open files can also be removed on Windows
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			for ( SpillFile spill : LIVE_SPILL_FILES )
				spill.run();
		}, "maxwell-spill-file-cleanup"));
	}

	private final long maxInMemoryElements;
	private final LinkedList<T> list;
	private long elementsInFile = 0;
	private SpillFile spill;
	private Cleaner.Cleanable cleanable;

	public ListWithDiskBuffer(long maxInMemoryElements) {
		this.maxInMemoryElements = maxInMemoryElements;
		list = new LinkedList<>();
	}

	public void add(T element) throws IOException {
		list.add(element);

		while ( shouldBuffer() )
			evict();
	}

	protected boolean shouldBuffer() {
		return this.list.size() > maxInMemoryElements;
	}

	protected void resetOutputStreamCaches() throws IOException {
		LOGGER.debug("Resetting OutputStream caches. elementsInFile: {}", elementsInFile);
		spill.os.reset();
	}

	public void flushToDisk() throws IOException {
		if ( spill != null )
			spill.os.flush();
	}

	public boolean isEmpty() {
		return this.size() == 0;
	}

	public T getLast() {
		return list.getLast();
	}

	public T removeFirst(Class<T> clazz) throws IOException, ClassNotFoundException {
		if ( elementsInFile > 0 ) {
			if ( spill.is == null ) {
				spill.os.flush();
				spill.is = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(spill.path)));
			}

			Object object = spill.is.readObject();
			T element = clazz.cast(object);
			elementsInFile--;

			if ( elementsInFile == 0 )
				deleteSpillFile();

			return element;
		} else {
			return list.removeFirst();
		}
	}

	public Long size() {
		return list.size() + elementsInFile;
	}

	public Long inMemorySize() {
		return Long.valueOf(list.size());
	}

	protected Path getSpillFile() {
		return spill == null ? null : spill.path;
	}

	/**
	 * Discard any buffered elements and delete the spill file, if any.
	 */
	@Override
	public void close() {
		list.clear();
		deleteSpillFile();
	}

	private void deleteSpillFile() {
		elementsInFile = 0;
		if ( cleanable != null )
			cleanable.clean();
		cleanable = null;
		spill = null;
	}

	protected T evict() throws IOException {
		if ( spill == null ) {
			spill = SpillFile.create();
			cleanable = CLEANER.register(this, spill);
		}

		if ( elementsInFile == 0 ) {
			LOGGER.info("Overflowed in-memory buffer, spilling over into " + spill.path);
		}

		T evicted = this.list.removeFirst();
		spill.os.writeObject(evicted);

		elementsInFile++;

		if ( elementsInFile % maxInMemoryElements == 0 )
			resetOutputStreamCaches();

		return evicted;
	}

	/* Holds no reference to the owning buffer so it can serve as its Cleaner action. */
	private static final class SpillFile implements Runnable {
		private static final FileAttribute<?>[] OWNER_ONLY;

		static {
			if ( FileSystems.getDefault().supportedFileAttributeViews().contains("posix") ) {
				Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
				OWNER_ONLY = new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(perms) };
			} else {
				// Files.createTempFile restricts access to the owner on non-POSIX file systems
				OWNER_ONLY = new FileAttribute<?>[0];
			}
		}

		final Path path;
		final ObjectOutputStream os;
		volatile ObjectInputStream is;

		private SpillFile(Path path, ObjectOutputStream os) {
			this.path = path;
			this.os = os;
		}

		static SpillFile create() throws IOException {
			Path path = Files.createTempFile("maxwell", "events", OWNER_ONLY);
			try {
				ObjectOutputStream os = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
				SpillFile spill = new SpillFile(path, os);
				LIVE_SPILL_FILES.add(spill);
				return spill;
			} catch ( IOException | RuntimeException e ) {
				deleteQuietly(path);
				throw e;
			}
		}

		@Override
		public void run() {
			try {
				closeQuietly(is);
				closeQuietly(os);
				deleteQuietly(path);
			} finally {
				LIVE_SPILL_FILES.remove(this);
			}
		}

		private static void closeQuietly(Closeable c) {
			if ( c == null )
				return;
			try {
				c.close();
			} catch ( IOException e ) {
				LOGGER.debug("Failed to close spill file stream", e);
			}
		}

		static void deleteQuietly(Path path) {
			try {
				Files.deleteIfExists(path);
			} catch ( IOException e ) {
				LOGGER.warn("Failed to delete spill file " + path, e);
			}
		}
	}
}
