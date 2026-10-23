/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins what a servlet container does with the two URL forms of
 * {@link BitstreamByHandleRestController}: which request targets it accepts, and what the filename
 * looks like by the time a servlet reads it. It drives an embedded Tomcat over a raw socket, and it
 * applies whatever {@code server.tomcat.relaxed-path-chars} the shipped application.properties sets,
 * so it tracks the configuration this backend actually runs with.
 *
 * <p>MockMvc cannot stand in for this: it is handed an already-parsed path and never runs Tomcat's
 * request-line parser, so it is blind to this class of failure by construction.</p>
 */
public class BitstreamByHandleRequestTargetTest {

    private static final String BASE = "/api/core/bitstreams/handle/11858/00-test";

    private static Tomcat tomcat;
    private static int port;

    @BeforeClass
    public static void startContainer() throws Exception {
        tomcat = new Tomcat();
        tomcat.setSilent(true);
        tomcat.setBaseDir(Files.createTempDirectory("bbh-request-target").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", Files.createTempDirectory("bbh-docbase").toString());
        Tomcat.addServlet(ctx, "echo", new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setContentType("text/plain;charset=UTF-8");
                resp.getWriter().write(req.getPathInfo() + "|" + req.getParameter("filename"));
            }
        });
        ctx.addServletMappingDecoded("/*", "echo");
        String relaxed = shippedRelaxedPathChars();
        if (relaxed == null) {
            tomcat.getConnector();
        } else {
            tomcat.getConnector().setProperty("relaxedPathChars", relaxed);
        }
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterClass
    public static void stopContainer() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    public void percentEncodedQuoteInPathSegmentIsDeliveredIntact() throws Exception {
        String[] response = exchange(BASE + "/file%20%22quoted%22.txt");
        assertEquals("200", response[0]);
        assertEquals(BASE + "/file \"quoted\".txt|null", response[1]);
    }

    @Test
    public void decodedQuoteInPathSegmentIsRejected() throws Exception {
        assertEquals("400", exchange(BASE + "/file%20\"quoted\".txt")[0]);
    }

    @Test
    public void encodedBackslashInPathSegmentIsRejected() throws Exception {
        assertEquals("400", exchange(BASE + "/back%5Cslash.txt")[0]);
    }

    @Test
    public void percentEncodedQuoteInFilenameParamIsDeliveredIntact() throws Exception {
        String[] response = exchange(BASE + "?filename=file%20%22quoted%22.txt");
        assertEquals("200", response[0]);
        assertEquals(BASE + "|file \"quoted\".txt", response[1]);
    }

    @Test
    public void percentEncodedBackslashInFilenameParamIsDeliveredIntact() throws Exception {
        String[] response = exchange(BASE + "?filename=back%5Cslash.txt");
        assertEquals("200", response[0]);
        assertEquals(BASE + "|back\\slash.txt", response[1]);
    }

    @Test
    public void queryDelimitersInFilenameParamAreDeliveredIntact() throws Exception {
        String[] response = exchange(BASE + "?filename=a%26b%2Bc%23d.txt");
        assertEquals("200", response[0]);
        assertEquals(BASE + "|a&b+c#d.txt", response[1]);
    }

    @Test
    public void decodedQuoteInFilenameParamIsRejected() throws Exception {
        assertEquals("400", exchange(BASE + "?filename=file%20\"quoted\".txt")[0]);
    }

    /** Send one request line verbatim; return the status code and the response body. */
    private static String[] exchange(String requestTarget) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + requestTarget + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            byte[] raw = readAll(socket.getInputStream());
            int bodyStart = headerLength(raw);
            String head = new String(raw, 0, Math.min(bodyStart, 64), StandardCharsets.ISO_8859_1);
            String statusLine = head.split("\r\n")[0];
            String body = new String(raw, bodyStart, raw.length - bodyStart, StandardCharsets.UTF_8);
            return new String[] {statusLine.split(" ")[1], body};
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static int headerLength(byte[] raw) {
        for (int i = 3; i < raw.length; i++) {
            if (raw[i - 3] == '\r' && raw[i - 2] == '\n' && raw[i - 1] == '\r' && raw[i] == '\n') {
                return i + 1;
            }
        }
        return raw.length;
    }

    private static String shippedRelaxedPathChars() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = BitstreamByHandleRequestTargetTest.class
                .getResourceAsStream("/application.properties")) {
            if (in == null) {
                return null;
            }
            properties.load(in);
        }
        return properties.getProperty("server.tomcat.relaxed-path-chars");
    }
}
