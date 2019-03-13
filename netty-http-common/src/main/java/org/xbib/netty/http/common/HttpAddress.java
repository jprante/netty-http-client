package org.xbib.netty.http.common;

import org.jboss.netty.handler.codec.http.HttpVersion;
import org.xbib.net.URL;

import java.net.InetSocketAddress;

/**
 * A handle for host, port, HTTP version, secure transport flag of a channel for HTTP.
 */
public class HttpAddress implements PoolKey {

    private static final HttpVersion HTTP_2_0 = HttpVersion.valueOf("HTTP/2.0");

    private final String host;

    private final Integer port;

    private final HttpVersion version;

    private final Boolean secure;

    private InetSocketAddress inetSocketAddress;

    public static HttpAddress http1(String host) {
        return new HttpAddress(host, 80, HttpVersion.HTTP_1_1, false);
    }

    public static HttpAddress http1(String host, int port) {
        return new HttpAddress(host, port, HttpVersion.HTTP_1_1, false);
    }

    public static HttpAddress secureHttp1(String host) {
        return new HttpAddress(host, 443, HttpVersion.HTTP_1_1, true);
    }

    public static HttpAddress secureHttp1(String host, int port) {
        return new HttpAddress(host, port, HttpVersion.HTTP_1_1, true);
    }

    public static HttpAddress http2(String host) {
        return new HttpAddress(host, 443, HTTP_2_0, false);
    }

    public static HttpAddress http2(String host, int port) {
        return new HttpAddress(host, port, HTTP_2_0, false);
    }

    public static HttpAddress secureHttp2(String host) {
        return new HttpAddress(host, 443, HTTP_2_0, true);
    }

    public static HttpAddress secureHttp2(String host, int port) {
        return new HttpAddress(host, port, HTTP_2_0, true);
    }

    public static HttpAddress http1(URL url) {
        return new HttpAddress(url, HttpVersion.HTTP_1_1);
    }

    public static HttpAddress http2(URL url) {
        return new HttpAddress(url, HTTP_2_0);
    }

    public static HttpAddress of(URL url, HttpVersion httpVersion) {
        return new HttpAddress(url, httpVersion);
    }

    public static HttpAddress of(String host, Integer port, HttpVersion version, boolean secure) {
        return new HttpAddress(host, port, version, secure);
    }

    public HttpAddress(URL url, HttpVersion version) {
        this(url.getHost(), url.getPort(), version, "https".equals(url.getScheme()));
    }

    public HttpAddress(String host, Integer port, HttpVersion version, boolean secure) {
        this.host = host;
        this.port = (port == null || port == -1) ? secure ? 443 : 80 : port;
        this.version = version;
        this.secure = secure;
    }

    public InetSocketAddress getInetSocketAddress() {
        if (inetSocketAddress == null) {
            // this may execute DNS lookup
            this.inetSocketAddress = new InetSocketAddress(host, port);
        }
        return inetSocketAddress;
    }

    public URL base() {
        return isSecure() ? URL.https().host(host).port(port).build() : URL.http().host(host).port(port).build();
    }

    public HttpVersion getVersion() {
        return version;
    }

    public boolean isSecure() {
        return secure;
    }

    public String toString() {
        return host + ":" + port + " (version:" + version + ",secure:" + secure + ")";
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof HttpAddress &&
                host.equals(((HttpAddress) object).host) &&
                (port != null && port.equals(((HttpAddress) object).port)) &&
                version.equals(((HttpAddress) object).version) &&
                secure.equals(((HttpAddress) object).secure);
    }

    @Override
    public int hashCode() {
        return host.hashCode() ^ port ^ version.hashCode() ^ secure.hashCode();
    }
}
