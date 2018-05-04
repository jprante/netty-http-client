package org.xbib.netty.http.server.test;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;
import org.xbib.netty.http.server.security.tls.SelfSignedCertificate;

import java.security.Security;
import java.util.logging.Logger;

public class SelfSignedCertificateTest {

    @Test
    public void testSelfSignedCertificate() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate("localhost");
        selfSignedCertificate.exportPEM(Logger.getLogger("test"));
    }
}
