package org.xbib.netty.http.server.test;

import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.xbib.netty.http.client.Client;
import org.xbib.netty.http.client.Request;
import org.xbib.netty.http.common.HttpAddress;
import org.xbib.netty.http.server.Server;
import org.xbib.netty.http.server.Domain;
import org.xbib.netty.http.server.endpoint.service.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NettyHttpExtension.class)
class SecureFileServiceTest {

    private static final Logger logger = Logger.getLogger(SecureFileServiceTest.class.getName());

    @Disabled
    @Test
    void testSecureFileServerHttp1() throws Exception {
        Path vartmp = Paths.get("/var/tmp/");
        HttpAddress httpAddress = HttpAddress.secureHttp1("localhost", 8143);
        Server server = Server.builder(Domain.builder(httpAddress, "*")
                  .setJdkSslProvider()
                  .setSelfCert()
                  .singleEndpoint("/static", "/**", new FileService(vartmp))
                .build())
                .setChildThreadCount(8)
                .build();
        //server.logDiagnostics(Level.INFO);
        Client client = Client.builder()
                .setJdkSslProvider()
                .trustInsecure()
                .build();
        //client.logDiagnostics(Level.INFO);
        final AtomicBoolean success = new AtomicBoolean(false);
        try {
            Files.write(vartmp.resolve("test.txt"), "Hello Jörg".getBytes(StandardCharsets.UTF_8));
            server.accept();
            Request request = Request.get()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .url(server.getServerConfig().getAddress().base().resolve("/static/test.txt"))
                    .build()
                    .setResponseListener(resp -> {
                        assertEquals("Hello Jörg", resp.getBodyAsString(StandardCharsets.UTF_8));
                        success.set(true);
                    });
            logger.log(Level.INFO, request.toString());
            client.execute(request).get();
            logger.log(Level.INFO, "request complete");
        } finally {
            server.shutdownGracefully();
            client.shutdownGracefully();
            Files.delete(vartmp.resolve("test.txt"));
            logger.log(Level.INFO, "server and client shut down");
        }
        assertTrue(success.get());
    }

    @Test
    void testSecureFileServerHttp2() throws Exception {
        Path vartmp = Paths.get("/var/tmp/");
        HttpAddress httpAddress = HttpAddress.secureHttp2("localhost", 8143);
        Server server = Server.builder(Domain.builder(httpAddress, "*")
                  .setOpenSSLSslProvider()
                  //.setJdkSslProvider()
                  .setSelfCert()
                  .singleEndpoint("/static", "/**", new FileService(vartmp))
                  .build())
                .enableDebug()
                .build();
        Client client = Client.builder()
                .setOpenSSLSslProvider()
                //.setJdkSslProvider()
                .trustInsecure()
                .build();
        final AtomicBoolean success = new AtomicBoolean(false);
        try {
            Files.write(vartmp.resolve("test.txt"), "Hello Jörg".getBytes(StandardCharsets.UTF_8));
            server.accept();
            Request request = Request.get()
                    .setVersion(HttpVersion.valueOf("HTTP/2.0"))
                    .url(server.getServerConfig().getAddress().base().resolve("/static/test.txt"))
                    .build()
                    .setResponseListener(resp -> {
                        assertEquals("Hello Jörg", resp.getBodyAsString(StandardCharsets.UTF_8));
                        success.set(true);
                    });
            logger.log(Level.INFO, request.toString());
            client.execute(request).get();
            logger.log(Level.INFO, "request complete");
        } finally {
            server.shutdownGracefully();
            client.shutdownGracefully();
            Files.delete(vartmp.resolve("test.txt"));
            logger.log(Level.INFO, "server and client shut down");
        }
        assertTrue(success.get());
    }

    /**
     * Connect HTTP 1.1 client to a HTTP 2.0 server.
     */
    @Disabled
    @Test
    void testSecureFileServerMixHttp1Http2() throws Exception {
        Path vartmp = Paths.get("/var/tmp/");
        HttpAddress httpAddress = HttpAddress.secureHttp2("localhost", 8143);
        Server server = Server.builder(Domain.builder(httpAddress, "*")
                .setOpenSSLSslProvider()
                .setSelfCert()
                .singleEndpoint("/static", "/**", new FileService(vartmp))
                .build())
                .build();
        Client client = Client.builder()
                .setOpenSSLSslProvider()
                .trustInsecure()
                .build();
        final AtomicBoolean success = new AtomicBoolean(false);
        try {
            Files.write(vartmp.resolve("test.txt"), "Hello Jörg".getBytes(StandardCharsets.UTF_8));
            server.accept();
            Request request = Request.get()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .url(server.getServerConfig().getAddress().base().resolve("/static/test.txt"))
                    .build()
                    .setResponseListener(resp -> {
                        assertEquals("Hello Jörg", resp.getBodyAsString(StandardCharsets.UTF_8));
                        success.set(true);
                    });
            logger.log(Level.INFO, request.toString());
            client.execute(request).get();
            logger.log(Level.INFO, "request complete");
        } finally {
            server.shutdownGracefully();
            client.shutdownGracefully();
            Files.delete(vartmp.resolve("test.txt"));
            logger.log(Level.INFO, "server and client shut down");
        }
        assertTrue(success.get());
    }
}