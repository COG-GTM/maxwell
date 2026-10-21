package com.zendesk.maxwell;

import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.conf.ProtocolConfiguration;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RaftExampleConfigTest {
	private List<ProtocolConfiguration> loadExampleStack() throws Exception {
		File example = new File("raft.xml.example");
		assertTrue("raft.xml.example should exist at the project root", example.exists());
		return ConfiguratorFactory.getStackConfigurator(example.getPath()).getProtocolStack();
	}

	private ProtocolConfiguration find(List<ProtocolConfiguration> stack, String name) {
		return stack.stream().filter(p -> p.getProtocolName().equals(name)).findFirst().orElse(null);
	}

	@Test
	public void testExampleDoesNotUseMulticastDiscovery() throws Exception {
		List<String> names = loadExampleStack().stream()
			.map(ProtocolConfiguration::getProtocolName)
			.collect(Collectors.toList());

		assertEquals("TCP", names.get(0));
		assertTrue(names.contains("TCPPING"));
		assertFalse(names.contains("UDP"));
		assertFalse(names.contains("PING"));
	}

	@Test
	public void testExampleEncryptsAndAuthenticatesClusterTraffic() throws Exception {
		List<ProtocolConfiguration> stack = loadExampleStack();
		List<String> names = stack.stream().map(ProtocolConfiguration::getProtocolName).collect(Collectors.toList());

		ProtocolConfiguration encrypt = find(stack, "SYM_ENCRYPT");
		assertNotNull("SYM_ENCRYPT must be configured", encrypt);
		assertTrue("SYM_ENCRYPT must sit below NAKACK2",
			names.indexOf("SYM_ENCRYPT") < names.indexOf("pbcast.NAKACK2"));

		ProtocolConfiguration auth = find(stack, "AUTH");
		assertNotNull("AUTH must be configured", auth);
		assertEquals("AUTH must sit directly below GMS",
			names.indexOf("pbcast.GMS") - 1, names.indexOf("AUTH"));
		Map<String, String> authProps = auth.getProperties();
		assertEquals("org.jgroups.auth.X509Token", authProps.get("auth_class"));
	}

	@Test
	public void testExampleDoesNotShipSecrets() throws Exception {
		List<ProtocolConfiguration> stack = loadExampleStack();
		assertEquals("${MAXWELL_RAFT_KEYSTORE_PASSWORD}", find(stack, "SYM_ENCRYPT").getProperties().get("store_password"));
		Map<String, String> authProps = find(stack, "AUTH").getProperties();
		assertEquals("${MAXWELL_RAFT_AUTH_VALUE}", authProps.get("auth_value"));
		assertEquals("${MAXWELL_RAFT_KEYSTORE_PASSWORD}", authProps.get("keystore_password"));
	}
}
