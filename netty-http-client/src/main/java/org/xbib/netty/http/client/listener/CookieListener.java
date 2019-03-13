package org.xbib.netty.http.client.listener;

import org.jboss.netty.handler.codec.http.cookie.Cookie;

@FunctionalInterface
public interface CookieListener {

    void onCookie(Cookie cookie);
}
