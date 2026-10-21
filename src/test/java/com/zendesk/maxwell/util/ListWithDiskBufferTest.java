package com.zendesk.maxwell.util;

import org.junit.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeTrue;

public class ListWithDiskBufferTest {
	private ListWithDiskBuffer<String> spilledBuffer(int inMemory, int total) throws Exception {
		ListWithDiskBuffer<String> buffer = new ListWithDiskBuffer<>(inMemory);
		for ( int i = 0; i < total; i++ )
			buffer.add("row-" + i);
		return buffer;
	}

	@Test
	public void spillFileIsOwnerOnly() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		ListWithDiskBuffer<String> buffer = spilledBuffer(2, 5);
		Path spill = buffer.getSpillFile();

		assertThat(spill, notNullValue());
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(spill)), is("rw-------"));
		buffer.close();
	}

	@Test
	public void spillFileIsDeletedOnceDrained() throws Exception {
		ListWithDiskBuffer<String> buffer = spilledBuffer(2, 5);
		Path spill = buffer.getSpillFile();
		assertThat(Files.exists(spill), is(true));

		assertThat(buffer.removeFirst(String.class), is("row-0"));
		assertThat(buffer.removeFirst(String.class), is("row-1"));
		assertThat(Files.exists(spill), is(true));
		assertThat(buffer.removeFirst(String.class), is("row-2"));

		assertThat(Files.exists(spill), is(false));
		assertThat(buffer.getSpillFile(), nullValue());
		assertThat(buffer.size(), is(2L));
		assertThat(buffer.removeFirst(String.class), is("row-3"));
		assertThat(buffer.removeFirst(String.class), is("row-4"));
		assertThat(buffer.isEmpty(), is(true));
	}

	@Test
	public void respillsIntoFreshFileAfterDrain() throws Exception {
		ListWithDiskBuffer<String> buffer = spilledBuffer(1, 3);
		Path first = buffer.getSpillFile();
		assertThat(buffer.removeFirst(String.class), is("row-0"));
		assertThat(buffer.removeFirst(String.class), is("row-1"));
		assertThat(Files.exists(first), is(false));

		buffer.add("row-3");
		buffer.add("row-4");
		Path second = buffer.getSpillFile();
		assertThat(second, notNullValue());

		assertThat(buffer.removeFirst(String.class), is("row-2"));
		assertThat(buffer.removeFirst(String.class), is("row-3"));
		assertThat(buffer.removeFirst(String.class), is("row-4"));
		assertThat(Files.exists(second), is(false));
		assertThat(buffer.isEmpty(), is(true));
	}

	@Test
	public void closeDeletesUndrainedSpillFile() throws Exception {
		ListWithDiskBuffer<String> buffer = spilledBuffer(2, 10);
		buffer.flushToDisk();
		Path spill = buffer.getSpillFile();
		assertThat(Files.size(spill) > 0, is(true));

		buffer.close();

		assertThat(Files.exists(spill), is(false));
		assertThat(buffer.isEmpty(), is(true));
		buffer.close();
	}

	@Test
	public void closeAfterPartialReadDeletesSpillFile() throws Exception {
		ListWithDiskBuffer<String> buffer = spilledBuffer(2, 10);
		Path spill = buffer.getSpillFile();
		assertThat(buffer.removeFirst(String.class), is("row-0"));

		buffer.close();

		assertThat(Files.exists(spill), is(false));
	}
}
