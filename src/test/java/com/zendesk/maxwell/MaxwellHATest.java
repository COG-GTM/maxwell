package com.zendesk.maxwell;

import org.jgroups.JChannel;
import org.jgroups.protocols.AUTH;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.jgroups.protocols.TCP;
import org.jgroups.protocols.UDP;
import org.jgroups.protocols.raft.InMemoryLog;
import org.jgroups.protocols.raft.RAFT;
import org.jgroups.raft.RaftHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class MaxwellHATest {
	private static final String EXAMPLE_CONFIG = "raft.xml.example";
	private static final String PASSWORD = "keystore-pass";
	private static final String SECRET = "cluster-secret";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final List<JChannel> channels = new ArrayList<>();
	private Properties savedProperties;
	private int[] ports;
	private File keystore;

	@Before
	public void setUp() throws Exception {
		savedProperties = (Properties) System.getProperties().clone();
		ports = new int[] { freePort(), freePort(), freePort(), freePort() };
		keystore = writeKeystore("cluster.keystore");
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("jgroups.tcpping.initial_hosts",
			"127.0.0.1[" + ports[0] + "],127.0.0.1[" + ports[1] + "],127.0.0.1[" + ports[2] + "]");
	}

	@After
	public void tearDown() {
		for ( JChannel ch : channels )
			ch.close();
		System.setProperties(savedProperties);
	}

	@Test
	public void testExampleConfigIsTcpEncryptedAndAuthenticated() throws Exception {
		JChannel ch = channel(ports[0], keystore, PASSWORD, SECRET);

		assertNotNull(ch.getProtocolStack().findProtocol(TCP.class));
		assertNull(ch.getProtocolStack().findProtocol(UDP.class));
		assertNotNull(ch.getProtocolStack().findProtocol(SYM_ENCRYPT.class));
		assertNotNull(ch.getProtocolStack().findProtocol(AUTH.class));
		assertFalse(MaxwellHA.warnIfInsecure(ch.getProtocolStack()));
	}

	@Test
	public void testWarnsOnUnauthenticatedMulticastConfig() throws Exception {
		String legacy = "<config xmlns=\"urn:org:jgroups\">"
			+ "<UDP mcast_addr=\"228.8.8.8\"/><PING/><pbcast.NAKACK2/><UNICAST3/><pbcast.STABLE/><pbcast.GMS/>"
			+ "</config>";
		JChannel ch = new JChannel(new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)));
		channels.add(ch);

		assertTrue(MaxwellHA.warnIfInsecure(ch.getProtocolStack()));
	}

	@Test
	public void testRequiresKeystorePasswordAndAuthSecret() throws Exception {
		System.setProperty("jgroups.bind_port", String.valueOf(ports[0]));
		System.setProperty("jgroups.encrypt.keystore", keystore.getAbsolutePath());
		System.setProperty("jgroups.auth.secret", SECRET);
		System.clearProperty("jgroups.encrypt.keystore_password");
		assertConnectFails();

		System.setProperty("jgroups.encrypt.keystore_password", PASSWORD);
		System.clearProperty("jgroups.auth.secret");
		assertConnectFails();
	}

	@Test
	public void testElectsLeaderAndKeepsOutNodesWithoutClusterCredentials() throws Exception {
		RaftHandle a = raftNode("A", ports[0], keystore, PASSWORD, SECRET);
		RaftHandle b = raftNode("B", ports[1], keystore, PASSWORD, SECRET);
		RaftHandle c = raftNode("C", ports[2], keystore, PASSWORD, SECRET);
		RaftHandle[] cluster = { a, b, c };

		waitFor(() -> a.channel().getView().size() == 3 && leaderOf(cluster) != null);
		String leader = leaderOf(cluster);
		assertNotNull("cluster should elect a leader", leader);

		// right AES key, wrong JOIN secret: AUTH rejects the join
		JChannel badSecret = channel(ports[3], keystore, PASSWORD, "not-the-secret");
		raftId(badSecret, leader);
		try {
			badSecret.connect("maxwell");
			fail("join with the wrong auth secret should be rejected");
		} catch ( Exception expected ) { }

		// right JOIN secret, different AES key: cluster traffic can't be read or injected
		File otherKey = writeKeystore("rogue.keystore");
		RaftHandle rogue = raftNode(leader, ports[3], otherKey, PASSWORD, SECRET);

		Thread.sleep(3000);
		assertEquals(1, rogue.channel().getView().size());
		for ( RaftHandle h : cluster )
			assertEquals(3, h.channel().getView().size());
		assertEquals(leader, leaderOf(cluster));
	}

	private void assertConnectFails() {
		JChannel ch = null;
		try {
			ch = new JChannel(EXAMPLE_CONFIG);
			channels.add(ch);
			ch.connect("maxwell");
			fail("node should not start without cluster credentials");
		} catch ( Exception expected ) {
		} finally {
			if ( ch != null )
				ch.close();
		}
	}

	private JChannel channel(int port, File keystore, String password, String secret) throws Exception {
		System.setProperty("jgroups.bind_port", String.valueOf(port));
		System.setProperty("jgroups.encrypt.keystore", keystore.getAbsolutePath());
		System.setProperty("jgroups.encrypt.keystore_password", password);
		System.setProperty("jgroups.auth.secret", secret);
		JChannel ch = new JChannel(EXAMPLE_CONFIG);
		channels.add(ch);
		RAFT raft = ch.getProtocolStack().findProtocol(RAFT.class);
		raft.logClass(InMemoryLog.class.getName());
		return ch;
	}

	private void raftId(JChannel ch, String id) {
		RAFT raft = ch.getProtocolStack().findProtocol(RAFT.class);
		raft.raftId(id);
	}

	private RaftHandle raftNode(String id, int port, File keystore, String password, String secret) throws Exception {
		JChannel ch = channel(port, keystore, password, secret);
		RaftHandle handle = new RaftHandle(ch, null).raftId(id);
		ch.connect("maxwell");
		return handle;
	}

	private static String leaderOf(RaftHandle[] cluster) {
		for ( RaftHandle h : cluster )
			if ( h.isLeader() )
				return h.raftId();
		return null;
	}

	private File writeKeystore(String name) throws Exception {
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		gen.init(256);
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(null, null);
		ks.setEntry("maxwell-ha", new KeyStore.SecretKeyEntry(gen.generateKey()),
			new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
		File f = tmp.newFile(name);
		try ( FileOutputStream out = new FileOutputStream(f) ) {
			ks.store(out, PASSWORD.toCharArray());
		}
		return f;
	}

	private static int freePort() throws Exception {
		try ( ServerSocket s = new ServerSocket(0) ) {
			return s.getLocalPort();
		}
	}

	private interface Condition { boolean met(); }

	private static void waitFor(Condition c) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 20000;
		while ( !c.met() && System.currentTimeMillis() < deadline )
			Thread.sleep(100);
	}
}
