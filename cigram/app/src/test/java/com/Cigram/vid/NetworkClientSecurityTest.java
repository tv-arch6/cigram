package com.Cigram.vid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.cert.Certificate;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import okhttp3.OkHttpClient;

import org.junit.Test;

/**
 * Regression test for the 18.2 security fix: the shared client behind the catalogue / account / search requests used to
 * install a trust-all X509TrustManager and a HostnameVerifier that always answered true.
 */
public class NetworkClientSecurityTest {

    private static OkHttpClient client() throws Exception {
        Method m = RequestNetworkController.class.getDeclaredMethod("getClient");
        m.setAccessible(true);
        return (OkHttpClient) m.invoke(RequestNetworkController.getInstance());
    }

    /** A session whose peer certificate cannot be verified (what a man-in-the-middle or a wrong host produces). */
    private static SSLSession unverifiedSession() {
        InvocationHandler h = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("getPeerCertificates".equals(method.getName())) throw new SSLPeerUnverifiedException("peer not verified");
                if ("getPeerCertificateChain".equals(method.getName())) throw new SSLPeerUnverifiedException("peer not verified");
                return null;
            }
        };
        return (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(), new Class<?>[]{SSLSession.class}, h);
    }

    @Test public void hostnameVerificationIsNotDisabled() throws Exception {
        assertFalse("a session with an unverifiable certificate must be refused", client().hostnameVerifier().verify("cigram-auth-api.example.invalid", unverifiedSession()));
    }

    @Test public void certificateChainsAreValidated() throws Exception {
        // the old trust-all manager accepted anything, including an empty chain; the platform manager refuses it
        javax.net.ssl.X509TrustManager tm = client().x509TrustManager();
        assertTrue("a trust manager is present", tm != null);
        boolean refused = false;
        try {
            tm.checkServerTrusted(new java.security.cert.X509Certificate[0], "RSA");
        } catch (java.security.cert.CertificateException | IllegalArgumentException expected) {
            refused = true;
        }
        assertTrue("an empty / untrusted chain must be refused", refused);
    }

    @Test public void timeoutsAreStillConfigured() throws Exception {
        OkHttpClient c = client();
        assertTrue(c.connectTimeoutMillis() == 15000 && c.readTimeoutMillis() == 25000 && c.writeTimeoutMillis() == 25000);
    }
}
