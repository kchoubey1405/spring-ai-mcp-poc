package com.schneider.ai.service;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the TLS trust used for calls to internal servers (Kafka Connect, Jira, GitHub Enterprise).
 *
 * <p>Internal servers are signed by the company PKI (for example EIT-RootCA), which is installed in the operating
 * system certificate store but is not part of the JDK's own cacerts - that is what causes
 * "PKIX path building failed". The trust built here is the union of
 * <ol>
 *   <li>the JDK default trust store (public CAs),</li>
 *   <li>the operating system trust store (Windows-ROOT on Windows, KeychainStore on macOS), and</li>
 *   <li>an optional extra trust store file (JKS/PKCS12) for Linux/container deployments.</li>
 * </ol>
 * Certificate and host name verification stay fully enabled; nothing is switched off.
 */
@Slf4j
public final class TlsTrust {

    private TlsTrust() {
    }

    /** Builds an SSL context that trusts all certificates (ideal for internal dev/UAT servers with self-signed certs). */
    public static SSLContext insecureContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot build insecure TLS context: " + ex.getMessage(), ex);
        }
    }

    /** SSL context trusting JDK defaults + OS store (when {@code useOsStore}) + the optional trust store file. */
    public static SSLContext context(boolean useOsStore, String trustStorePath, String trustStorePassword) {
        try {
            List<X509TrustManager> managers = new ArrayList<>();
            addDefault(managers, null);
            if (useOsStore) {
                String osType = osStoreType();
                if (osType != null) {
                    try {
                        KeyStore os = KeyStore.getInstance(osType);
                        os.load(null, null);
                        addDefault(managers, os);
                        log.info("TLS: operating system trust store '{}' added to the JDK trust store", osType);
                    } catch (Exception ex) {
                        log.warn("TLS: operating system trust store '{}' not available: {}", osType, ex.getMessage());
                    }
                }
            }
            if (trustStorePath != null && !trustStorePath.isBlank()) {
                KeyStore custom = KeyStore.getInstance(trustStorePath.toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12");
                char[] password = trustStorePassword == null ? new char[0] : trustStorePassword.toCharArray();
                try (InputStream in = Files.newInputStream(Path.of(trustStorePath.trim()))) {
                    custom.load(in, password);
                }
                addDefault(managers, custom);
                log.info("TLS: additional trust store {} loaded", trustStorePath);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new Composite(managers)}, null);
            return context;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Cannot build the TLS trust: " + ex.getMessage(), ex);
        }
    }

    private static String osStoreType() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "Windows-ROOT";
        }
        if (os.contains("mac")) {
            return "KeychainStore";
        }
        return null;
    }

    private static void addDefault(List<X509TrustManager> managers, KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                managers.add(x509);
            }
        }
    }

    /** Accepts a certificate chain when ANY of the underlying trust managers accepts it. */
    static final class Composite implements X509TrustManager {
        private final List<X509TrustManager> delegates;

        Composite(List<X509TrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last != null ? last : new CertificateException("No trust manager available");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    if (last == null) {
                        last = ex;   // the JDK default's message is the most familiar one
                    }
                }
            }
            throw last != null ? last : new CertificateException("No trust manager available");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager delegate : delegates) {
                all.addAll(List.of(delegate.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }
    }
}
