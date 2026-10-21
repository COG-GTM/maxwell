package com.zendesk.maxwell.producer;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.row.RowMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.TimeoutException;

public class RabbitmqProducer extends AbstractProducer {

	private static final Logger LOGGER = LoggerFactory.getLogger(RabbitmqProducer.class);
	private static String exchangeName;
	private static BasicProperties props;
	private Channel channel;
	public RabbitmqProducer(MaxwellContext context) {
		super(context);
		exchangeName = context.getConfig().rabbitmqExchange;
		props = context.getConfig().rabbitmqMessagePersistent ? MessageProperties.MINIMAL_PERSISTENT_BASIC : null;

		try {
			this.channel = createConnectionFactory(context.getConfig()).newConnection().createChannel();
			if(context.getConfig().rabbitmqDeclareExchange) {
				this.channel.exchangeDeclare(exchangeName, context.getConfig().rabbitmqExchangeType, context.getConfig().rabbitMqExchangeDurable, context.getConfig().rabbitMqExchangeAutoDelete, null);
			}
		} catch (IOException | TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

	static ConnectionFactory createConnectionFactory(MaxwellConfig config) {
		ConnectionFactory factory = new ConnectionFactory();
		boolean useTls = isTlsRequested(config);

		try {
			if ( useTls ) {
				// Must happen before setUri(): for an amqps:// URI without an SSL context,
				// amqp-client falls back to a trust-everything TrustManager.
				factory.useSslProtocol(buildSslContext(config));
				factory.enableHostnameVerification();
			} else if ( hasTlsStoreOptions(config) ) {
				throw new IllegalArgumentException("rabbitmq_ssl_truststore/rabbitmq_ssl_keystore are set but TLS is not enabled; "
					+ "set rabbitmq_use_ssl=true or use an amqps:// rabbitmq_uri");
			}

			if ( config.rabbitmqURI != null ) {
				factory.setUri(config.rabbitmqURI);
			}

			if ( config.rabbitmqHost != null ) {
				factory.setHost(config.rabbitmqHost);
			} else if ( config.rabbitmqURI == null ) {
				factory.setHost("localhost");
			}

			if ( config.rabbitmqPort != null ) {
				factory.setPort(config.rabbitmqPort);
			}

			if ( config.rabbitmqUser != null ) {
				factory.setUsername(config.rabbitmqUser);
			}

			if ( config.rabbitmqPass != null ) {
				factory.setPassword(config.rabbitmqPass);
			}

			if ( config.rabbitmqVirtualHost != null ) {
				factory.setVirtualHost(config.rabbitmqVirtualHost);
			}

			if ( config.rabbitmqHandshakeTimeout != null ) {
				factory.setHandshakeTimeout(config.rabbitmqHandshakeTimeout);
			}
		} catch (GeneralSecurityException | IOException | URISyntaxException e) {
			throw new RuntimeException("Unable to configure RabbitMQ connection: " + e.getMessage(), e);
		}

		return factory;
	}

	static boolean isTlsRequested(MaxwellConfig config) {
		if ( config.rabbitmqUseSSL ) {
			return true;
		}

		if ( config.rabbitmqURI == null ) {
			return false;
		}

		try {
			String scheme = new URI(config.rabbitmqURI).getScheme();
			return "amqps".equalsIgnoreCase(scheme);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid rabbitmq_uri", e);
		}
	}

	private static boolean hasTlsStoreOptions(MaxwellConfig config) {
		return config.rabbitmqSslTruststore != null || config.rabbitmqSslKeystore != null;
	}

	static SSLContext buildSslContext(MaxwellConfig config) throws GeneralSecurityException, IOException {
		TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		// a null KeyStore makes the factory use the JVM default trust store
		trustManagerFactory.init(loadKeyStore(config.rabbitmqSslTruststore, config.rabbitmqSslTruststoreType, config.rabbitmqSslTruststorePassword));

		KeyManager[] keyManagers = null;
		if ( config.rabbitmqSslKeystore != null ) {
			char[] password = toChars(config.rabbitmqSslKeystorePassword);
			KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagerFactory.init(loadKeyStore(config.rabbitmqSslKeystore, config.rabbitmqSslKeystoreType, config.rabbitmqSslKeystorePassword), password);
			keyManagers = keyManagerFactory.getKeyManagers();
		}

		SSLContext context = SSLContext.getInstance("TLS");
		context.init(keyManagers, trustManagerFactory.getTrustManagers(), null);
		return context;
	}

	private static KeyStore loadKeyStore(String path, String type, String password) throws GeneralSecurityException, IOException {
		if ( path == null ) {
			return null;
		}

		KeyStore keyStore = KeyStore.getInstance(type != null ? type : KeyStore.getDefaultType());
		try ( InputStream in = new FileInputStream(path) ) {
			keyStore.load(in, toChars(password));
		}
		return keyStore;
	}

	private static char[] toChars(String password) {
		return password == null ? new char[0] : password.toCharArray();
	}

	@Override
	public void push(RowMap r) throws Exception {
		if ( !r.shouldOutput(outputConfig) ) {
			context.setPosition(r.getNextPosition());

			return;
		}

		String value = r.toJSON(outputConfig);
		String routingKey = getRoutingKeyFromTemplate(r);

		channel.basicPublish(exchangeName, routingKey, props, value.getBytes());
		if ( r.isTXCommit() ) {
			context.setPosition(r.getNextPosition());
		}
		if ( LOGGER.isDebugEnabled()) {
			LOGGER.debug("->  routing key:{}, partition:{}", routingKey, value);
		}
	}

	private String getRoutingKeyFromTemplate(RowMap r) {
		String table = r.getTable();

		if ( table == null )
			table = "";

		return context
				.getConfig()
				.rabbitmqRoutingKeyTemplate
				.replace("%db%", r.getDatabase())
				.replace("%table%", table);
	}
}
