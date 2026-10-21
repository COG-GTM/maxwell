package com.zendesk.maxwell.producer;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zendesk.maxwell.MaxwellConfig;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RabbitmqProducerTest {
	private static final String PASSWORD = "changeit";
	private static final byte[] AMQP_HEADER = new byte[] { 'A', 'M', 'Q', 'P', 0, 0, 9, 1 };

	@ClassRule
	public static TemporaryFolder tmp = new TemporaryFolder();

	private static File localhostKeystore;
	private static File localhostTruststore;
	private static File wrongHostKeystore;
	private static File wrongHostTruststore;
	private static File clientKeystore;
	private static File clientTruststore;

	@BeforeClass
	public static void generateCertificates() throws Exception {
		localhostKeystore = keypair("localhost", "SAN=dns:localhost,ip:127.0.0.1");
		localhostTruststore = truststoreFor(localhostKeystore, "localhost");
		wrongHostKeystore = keypair("wronghost", "SAN=dns:wrong.example");
		wrongHostTruststore = truststoreFor(wrongHostKeystore, "wronghost");
		clientKeystore = keypair("client", "SAN=dns:maxwell-client");
		clientTruststore = truststoreFor(clientKeystore, "client");
	}

	@Test
	public void amqpsUriDoesNotTrustUnknownCertificates() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		try ( FakeTlsBroker broker = new FakeTlsBroker(localhostKeystore, null) ) {
			config.rabbitmqURI = "amqps://user:secret@localhost:" + broker.port();

			assertFalse(connect(config));
			assertFalse("credentials must not be sent to an unauthenticated peer", broker.receivedAmqpHeader());
		}
	}

	@Test
	public void amqpsUriConnectsWhenBrokerCertificateIsTrusted() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		try ( FakeTlsBroker broker = new FakeTlsBroker(localhostKeystore, null) ) {
			config.rabbitmqURI = "amqps://user:secret@localhost:" + broker.port();
			config.rabbitmqSslTruststore = localhostTruststore.getAbsolutePath();
			config.rabbitmqSslTruststorePassword = PASSWORD;

			connect(config);
			assertTrue(broker.receivedAmqpHeader());
		}
	}

	@Test
	public void useSslRejectsTrustedCertificateForAnotherHost() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		try ( FakeTlsBroker broker = new FakeTlsBroker(wrongHostKeystore, null) ) {
			config.rabbitmqUseSSL = true;
			config.rabbitmqHost = "localhost";
			config.rabbitmqPort = broker.port();
			config.rabbitmqSslTruststore = wrongHostTruststore.getAbsolutePath();
			config.rabbitmqSslTruststorePassword = PASSWORD;

			assertFalse(connect(config));
			assertFalse(broker.receivedAmqpHeader());
		}
	}

	@Test
	public void amqpsUriRejectsTrustedCertificateForAnotherHost() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		try ( FakeTlsBroker broker = new FakeTlsBroker(wrongHostKeystore, null) ) {
			config.rabbitmqURI = "amqps://localhost:" + broker.port();
			config.rabbitmqSslTruststore = wrongHostTruststore.getAbsolutePath();
			config.rabbitmqSslTruststorePassword = PASSWORD;

			assertFalse(connect(config));
			assertFalse(broker.receivedAmqpHeader());
		}
	}

	@Test
	public void useSslConnectsWhenHostnameMatches() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		try ( FakeTlsBroker broker = new FakeTlsBroker(localhostKeystore, null) ) {
			config.rabbitmqUseSSL = true;
			config.rabbitmqHost = "localhost";
			config.rabbitmqPort = broker.port();
			config.rabbitmqSslTruststore = localhostTruststore.getAbsolutePath();
			config.rabbitmqSslTruststorePassword = PASSWORD;

			connect(config);
			assertTrue(broker.receivedAmqpHeader());
		}
	}

	@Test
	public void presentsClientCertificateForMutualTls() throws Exception {
		MaxwellConfig config = new MaxwellConfig();
		config.rabbitmqUseSSL = true;
		config.rabbitmqHost = "localhost";
		config.rabbitmqSslTruststore = localhostTruststore.getAbsolutePath();
		config.rabbitmqSslTruststorePassword = PASSWORD;

		try ( FakeTlsBroker broker = new FakeTlsBroker(localhostKeystore, clientTruststore) ) {
			config.rabbitmqPort = broker.port();
			assertFalse(connect(config));
			assertFalse(broker.receivedAmqpHeader());
		}

		config.rabbitmqSslKeystore = clientKeystore.getAbsolutePath();
		config.rabbitmqSslKeystorePassword = PASSWORD;
		config.rabbitmqSslKeystoreType = "PKCS12";
		try ( FakeTlsBroker broker = new FakeTlsBroker(localhostKeystore, clientTruststore) ) {
			config.rabbitmqPort = broker.port();
			connect(config);
			assertTrue(broker.receivedAmqpHeader());
		}
	}

	@Test
	public void failsClosedWhenTruststoreCannotBeLoaded() {
		MaxwellConfig config = new MaxwellConfig();
		config.rabbitmqURI = "amqps://localhost";
		config.rabbitmqSslTruststore = new File(tmp.getRoot(), "missing.p12").getAbsolutePath();

		assertThrows(RuntimeException.class, () -> RabbitmqProducer.createConnectionFactory(config));
	}

	@Test
	public void rejectsTlsStoreOptionsWithoutTls() {
		MaxwellConfig config = new MaxwellConfig();
		config.rabbitmqURI = "amqp://localhost";
		config.rabbitmqSslTruststore = localhostTruststore.getAbsolutePath();

		assertThrows(IllegalArgumentException.class, () -> RabbitmqProducer.createConnectionFactory(config));
	}

	@Test
	public void plainConnectionsStayPlain() {
		MaxwellConfig config = new MaxwellConfig();
		config.rabbitmqURI = "amqp://localhost";
		ConnectionFactory factory = RabbitmqProducer.createConnectionFactory(config);

		assertFalse(factory.isSSL());
		assertEquals(ConnectionFactory.DEFAULT_AMQP_PORT, factory.getPort());
	}

	@Test
	public void tlsFactoryUsesTlsPortByDefault() {
		MaxwellConfig config = new MaxwellConfig();
		config.rabbitmqUseSSL = true;
		ConnectionFactory factory = RabbitmqProducer.createConnectionFactory(config);

		assertTrue(factory.isSSL());
		assertEquals(ConnectionFactory.DEFAULT_AMQP_OVER_SSL_PORT, factory.getPort());
	}

	private static boolean connect(MaxwellConfig config) {
		ConnectionFactory factory = RabbitmqProducer.createConnectionFactory(config);
		factory.setConnectionTimeout(5000);
		factory.setHandshakeTimeout(5000);
		factory.setAutomaticRecoveryEnabled(false);
		try ( Connection ignored = factory.newConnection() ) {
			return true;
		} catch ( Exception e ) {
			return false;
		}
	}

	private static File keypair(String alias, String san) throws Exception {
		File keystore = new File(tmp.getRoot(), alias + ".p12");
		keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
			"-dname", "CN=" + alias, "-ext", san, "-storetype", "PKCS12",
			"-keystore", keystore.getAbsolutePath(), "-storepass", PASSWORD, "-keypass", PASSWORD);
		return keystore;
	}

	private static File truststoreFor(File keystore, String alias) throws Exception {
		File cert = new File(tmp.getRoot(), alias + ".crt");
		File truststore = new File(tmp.getRoot(), alias + "-trust.p12");
		keytool("-exportcert", "-alias", alias, "-keystore", keystore.getAbsolutePath(),
			"-storepass", PASSWORD, "-file", cert.getAbsolutePath());
		keytool("-importcert", "-noprompt", "-alias", alias, "-file", cert.getAbsolutePath(),
			"-storetype", "PKCS12", "-keystore", truststore.getAbsolutePath(), "-storepass", PASSWORD);
		return truststore;
	}

	private static void keytool(String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
		System.arraycopy(args, 0, command, 1, args.length);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		byte[] output = readAll(process.getInputStream());
		if ( process.waitFor() != 0 ) {
			throw new IllegalStateException("keytool failed: " + new String(output));
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ( (n = in.read(buf)) != -1 ) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static KeyStore load(File file) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try ( InputStream in = new FileInputStream(file) ) {
			keyStore.load(in, PASSWORD.toCharArray());
		}
		return keyStore;
	}

	/**
	 * Accepts one TLS connection and records whether the client completed the handshake
	 * and went on to send the AMQP protocol header.
	 */
	private static class FakeTlsBroker implements AutoCloseable {
		private final SSLServerSocket serverSocket;
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		private final Future<Boolean> result;

		FakeTlsBroker(File serverKeystore, File clientTruststore) throws Exception {
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(load(serverKeystore), PASSWORD.toCharArray());
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(clientTruststore == null ? null : load(clientTruststore));
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

			serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
			serverSocket.setNeedClientAuth(clientTruststore != null);
			result = executor.submit(() -> {
				try ( SSLSocket socket = (SSLSocket) serverSocket.accept() ) {
					socket.setSoTimeout(5000);
					socket.startHandshake();
					byte[] header = new byte[AMQP_HEADER.length];
					new DataInputStream(socket.getInputStream()).readFully(header);
					return Arrays.equals(AMQP_HEADER, header);
				} catch ( IOException e ) {
					return false;
				}
			});
		}

		int port() {
			return serverSocket.getLocalPort();
		}

		boolean receivedAmqpHeader() throws Exception {
			return result.get(10, TimeUnit.SECONDS);
		}

		@Override
		public void close() throws IOException {
			serverSocket.close();
			executor.shutdownNow();
		}
	}
}
