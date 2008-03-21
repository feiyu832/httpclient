/*
 * $HeadURL$
 * $Revision$
 * $Date$
 * ====================================================================
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.http.impl.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ClientConnectionRequest;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.ConnectionReleaseTrigger;
import org.apache.http.conn.ManagedClientConnection;
import org.apache.http.conn.PlainSocketFactory;
import org.apache.http.conn.Scheme;
import org.apache.http.conn.SchemeRegistry;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.conn.ClientConnAdapterMockup;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.localserver.ServerTestBase;
import org.apache.http.message.BasicHeader;
import org.apache.http.mockup.SocketFactoryMockup;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;

/**
 * Unit tests for {@link DefaultClientRequestDirector}
 */
public class TestDefaultClientRequestDirector extends ServerTestBase {

    public TestDefaultClientRequestDirector(final String testName) throws IOException {
        super(testName);
    }

    public static void main(String args[]) {
        String[] testCaseName = { TestDefaultClientRequestDirector.class.getName() };
        junit.textui.TestRunner.main(testCaseName);
    }

    public static Test suite() {
        return new TestSuite(TestDefaultClientRequestDirector.class);
    }
    
    /**
     * Tests that if abort is called on an {@link AbortableHttpRequest} while
     * {@link DefaultClientRequestDirector} is allocating a connection, that the
     * connection is properly aborted.
     */
    public void testAbortInAllocate() throws Exception {
        CountDownLatch connLatch = new CountDownLatch(1);
        CountDownLatch awaitLatch = new CountDownLatch(1);
        final ConMan conMan = new ConMan(connLatch, awaitLatch);        
        final AtomicReference<Throwable> throwableRef = new AtomicReference<Throwable>();
        final CountDownLatch getLatch = new CountDownLatch(1);
        final DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams()); 
        final HttpContext context = client.getDefaultContext();
        final HttpGet httpget = new HttpGet("http://www.example.com/a");
        
        new Thread(new Runnable() {
            public void run() {
                try {
                    client.execute(httpget, context);
                } catch(Throwable t) {
                    throwableRef.set(t);
                } finally {
                    getLatch.countDown();
                }
            }
        }).start();
        
        assertTrue("should have tried to get a connection", connLatch.await(1, TimeUnit.SECONDS));
        
        httpget.abort();
        
        assertTrue("should have finished get request", getLatch.await(1, TimeUnit.SECONDS));
        assertTrue("should be instanceof IOException, was: " + throwableRef.get(),
                throwableRef.get() instanceof IOException);
        assertTrue("cause should be InterruptedException, was: " + throwableRef.get().getCause(),
                throwableRef.get().getCause() instanceof InterruptedException);
    }
    
    /**
     * Tests that an abort called after the connection has been retrieved
     * but before a release trigger is set does still abort the request.
     */
    public void testAbortAfterAllocateBeforeRequest() throws Exception {
        this.localServer.register("*", new BasicService());
        
        CountDownLatch releaseLatch = new CountDownLatch(1);
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        
        SingleClientConnManager conMan = new SingleClientConnManager(new BasicHttpParams(), registry);
        final AtomicReference<Throwable> throwableRef = new AtomicReference<Throwable>();
        final CountDownLatch getLatch = new CountDownLatch(1);
        final DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams()); 
        final HttpContext context = client.getDefaultContext();
        final HttpGet httpget = new CustomGet("a", releaseLatch);
        
        new Thread(new Runnable() {
            public void run() {
                try {
                    client.execute(getServerHttp(), httpget, context);
                } catch(Throwable t) {
                    throwableRef.set(t);
                } finally {
                    getLatch.countDown();
                }
            }
        }).start();
        
        Thread.sleep(100); // Give it a little time to proceed to release...
        
        httpget.abort();
        
        releaseLatch.countDown();
        
        assertTrue("should have finished get request", getLatch.await(1, TimeUnit.SECONDS));
        assertTrue("should be instanceof IOException, was: " + throwableRef.get(),
                throwableRef.get() instanceof IOException);
    }
    
    /**
     * Tests that an abort called completely before execute
     * still aborts the request.
     */
    public void testAbortBeforeExecute() throws Exception {
        this.localServer.register("*", new BasicService());
        
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        
        SingleClientConnManager conMan = new SingleClientConnManager(new BasicHttpParams(), registry);
        final AtomicReference<Throwable> throwableRef = new AtomicReference<Throwable>();
        final CountDownLatch getLatch = new CountDownLatch(1);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams()); 
        final HttpContext context = client.getDefaultContext();
        final HttpGet httpget = new HttpGet("a");
        
        new Thread(new Runnable() {
            public void run() {
                try {
                    try {
                        if(!startLatch.await(1, TimeUnit.SECONDS))
                            throw new RuntimeException("Took too long to start!");
                    } catch(InterruptedException interrupted) {
                        throw new RuntimeException("Never started!", interrupted);
                    }
                    client.execute(getServerHttp(), httpget, context);
                } catch(Throwable t) {
                    throwableRef.set(t);
                } finally {
                    getLatch.countDown();
                }
            }
        }).start();
        
        httpget.abort();
        startLatch.countDown();
        
        assertTrue("should have finished get request", getLatch.await(1, TimeUnit.SECONDS));
        assertTrue("should be instanceof IOException, was: " + throwableRef.get(),
                throwableRef.get() instanceof IOException);
    }
    
    /**
     * Tests that an abort called after a redirect has found a new host
     * still aborts in the correct place (while trying to get the new
     * host's route, not while doing the subsequent request).
     */
    public void testAbortAfterRedirectedRoute() throws Exception {
        final int port = this.localServer.getServicePort();
        this.localServer.register("*", new BasicRedirectService(port));
        
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        
        CountDownLatch connLatch = new CountDownLatch(1);
        CountDownLatch awaitLatch = new CountDownLatch(1);
        ConnMan4 conMan = new ConnMan4(new BasicHttpParams(), registry, connLatch, awaitLatch);
        final AtomicReference<Throwable> throwableRef = new AtomicReference<Throwable>();
        final CountDownLatch getLatch = new CountDownLatch(1);
        final DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams()); 
        final HttpContext context = client.getDefaultContext();
        final HttpGet httpget = new HttpGet("a");
        
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpHost host = new HttpHost("127.0.0.1", port);
                    client.execute(host, httpget, context);
                } catch(Throwable t) {
                    throwableRef.set(t);
                } finally {
                    getLatch.countDown();
                }
            }
        }).start();
        
        assertTrue("should have tried to get a connection", connLatch.await(1, TimeUnit.SECONDS));
        
        httpget.abort();
        
        assertTrue("should have finished get request", getLatch.await(1, TimeUnit.SECONDS));
        assertTrue("should be instanceof IOException, was: " + throwableRef.get(),
                throwableRef.get() instanceof IOException);
        assertTrue("cause should be InterruptedException, was: " + throwableRef.get().getCause(),
                throwableRef.get().getCause() instanceof InterruptedException);
    }
    
    
    /**
     * Tests that if a socket fails to connect, the allocated connection is
     * properly released back to the connection manager.
     */
    public void testSocketConnectFailureReleasesConnection() throws Exception {
        final ConnMan2 conMan = new ConnMan2();
        final DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams()); 
        final HttpContext context = client.getDefaultContext();
        final HttpGet httpget = new HttpGet("http://www.example.com/a");
        
        try {
            client.execute(httpget, context);
            fail("expected IOException");
        } catch(IOException expected) {}
        
        assertNotNull(conMan.allocatedConnection);
        assertSame(conMan.allocatedConnection, conMan.releasedConnection);
    }
    
    public void testRequestFailureReleasesConnection() throws Exception {
        this.localServer.register("*", new ThrowingService());

        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        
        ConnMan3 conMan = new ConnMan3(new BasicHttpParams(), registry);
        DefaultHttpClient client = new DefaultHttpClient(conMan, new BasicHttpParams());
        HttpGet httpget = new HttpGet("/a");

        try {
            client.execute(getServerHttp(), httpget);
            fail("expected IOException");
        } catch (IOException expected) {}

        assertNotNull(conMan.allocatedConnection);
        assertSame(conMan.allocatedConnection, conMan.releasedConnection);
    }
    
    private static class ThrowingService implements HttpRequestHandler {
        public void handle(
                final HttpRequest request, 
                final HttpResponse response, 
                final HttpContext context) throws HttpException, IOException {
            throw new IOException();
        }
    }
    
    private static class BasicService implements HttpRequestHandler {
        public void handle(final HttpRequest request, 
                final HttpResponse response, 
                final HttpContext context) throws HttpException, IOException {
            response.setStatusCode(200);
            response.setEntity(new StringEntity("Hello World"));
        }
    }
    
   private class BasicRedirectService implements HttpRequestHandler {
        private int statuscode = HttpStatus.SC_SEE_OTHER;
        private int port;

        public BasicRedirectService(int port) {
            this.port = port;
        }

        public void handle(final HttpRequest request,
                final HttpResponse response, final HttpContext context)
                throws HttpException, IOException {
            ProtocolVersion ver = request.getRequestLine().getProtocolVersion();
            response.setStatusLine(ver, this.statuscode);
            response.addHeader(new BasicHeader("Location", "http://localhost:"
                    + this.port + "/newlocation/"));
            response.addHeader(new BasicHeader("Connection", "close"));
        }
    }

    private static class ConnMan4 extends ThreadSafeClientConnManager {
        private final CountDownLatch connLatch;
        private final CountDownLatch awaitLatch;
        
        public ConnMan4(HttpParams params, SchemeRegistry schreg,
                CountDownLatch connLatch, CountDownLatch awaitLatch) {
            super(params, schreg);
            this.connLatch = connLatch;
            this.awaitLatch = awaitLatch;
        }
        
        @Override
        public ClientConnectionRequest requestConnection(HttpRoute route) {
            // If this is the redirect route, stub the return value
            // so-as to pretend the host is waiting on a slot...
            if(route.getTargetHost().getHostName().equals("localhost")) {
                final Thread currentThread = Thread.currentThread();
                
                return new ClientConnectionRequest() {
                    
                    public void abortRequest() {
                        currentThread.interrupt();
                    }
                    
                    public ManagedClientConnection getConnection(
                            long timeout, TimeUnit tunit)
                            throws InterruptedException,
                            ConnectionPoolTimeoutException {
                        connLatch.countDown(); // notify waiter that we're getting a connection
                        
                        // zero usually means sleep forever, but CountDownLatch doesn't interpret it that way.
                        if(timeout == 0)
                            timeout = Integer.MAX_VALUE;
                        
                        if(!awaitLatch.await(timeout, tunit))
                            throw new ConnectionPoolTimeoutException();
                        
                        return new ClientConnAdapterMockup();
                    }
                };  
            } else {
                return super.requestConnection(route);
            }
        }
    }
     
    
    private static class ConnMan3 extends SingleClientConnManager {
        private ManagedClientConnection allocatedConnection;
        private ManagedClientConnection releasedConnection;
        
        public ConnMan3(HttpParams params, SchemeRegistry schreg) {
            super(params, schreg);
        }
        
        @Override
        public ManagedClientConnection getConnection(HttpRoute route) {
            allocatedConnection = super.getConnection(route);
            return allocatedConnection;
        }
        
        @Override
        public void releaseConnection(ManagedClientConnection conn) {
            releasedConnection = conn;
            super.releaseConnection(conn);
        }
        
        
    }
    
    private static class ConnMan2 implements ClientConnectionManager {
        
        private ManagedClientConnection allocatedConnection;
        private ManagedClientConnection releasedConnection;
        
        public ConnMan2() {
        }

        public void closeIdleConnections(long idletime, TimeUnit tunit) {
            throw new UnsupportedOperationException("just a mockup");
        }

        public ManagedClientConnection getConnection(HttpRoute route)
                throws InterruptedException {
            throw new UnsupportedOperationException("just a mockup");
        }

        public ManagedClientConnection getConnection(HttpRoute route,
                long timeout, TimeUnit tunit) {
            throw new UnsupportedOperationException("just a mockup");
        }
        
        public ClientConnectionRequest requestConnection(final HttpRoute route) {
            
            return new ClientConnectionRequest() {
                
                public void abortRequest() {
                    throw new UnsupportedOperationException("just a mockup");
                }
                
                public ManagedClientConnection getConnection(
                        long timeout, TimeUnit unit)
                        throws InterruptedException,
                        ConnectionPoolTimeoutException {
                    allocatedConnection = new ClientConnAdapterMockup() {
                        @Override
                        public void open(HttpRoute route, HttpContext context,
                                HttpParams params) throws IOException {
                            throw new ConnectException();
                        }
                    };
                    return allocatedConnection;
                }
            };
        }

        public HttpParams getParams() {
            throw new UnsupportedOperationException("just a mockup");
        }

        public SchemeRegistry getSchemeRegistry() {
            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", new SocketFactoryMockup(null), 80));
            return registry;
        }

        public void releaseConnection(ManagedClientConnection conn) {
            this.releasedConnection = conn;
        }

        public void shutdown() {
            throw new UnsupportedOperationException("just a mockup");
        }
    }
    
    private static class ConMan implements ClientConnectionManager {
        private final CountDownLatch connLatch;
        private final CountDownLatch awaitLatch;
        
        public ConMan(CountDownLatch connLatch, CountDownLatch awaitLatch) {
            this.connLatch = connLatch;
            this.awaitLatch = awaitLatch;
        }

        public void closeIdleConnections(long idletime, TimeUnit tunit) {
            throw new UnsupportedOperationException("just a mockup");
        }

        public ManagedClientConnection getConnection(HttpRoute route)
                throws InterruptedException {
            throw new UnsupportedOperationException("just a mockup");
        }

        public ManagedClientConnection getConnection(HttpRoute route,
                long timeout, TimeUnit tunit) {
            throw new UnsupportedOperationException("just a mockup");
        }
        
        public ClientConnectionRequest requestConnection(final HttpRoute route) {
            
            final Thread currentThread = Thread.currentThread();
            
            return new ClientConnectionRequest() {
                
                public void abortRequest() {
                    currentThread.interrupt();
                }
                
                public ManagedClientConnection getConnection(
                        long timeout, TimeUnit tunit)
                        throws InterruptedException,
                        ConnectionPoolTimeoutException {
                    connLatch.countDown(); // notify waiter that we're getting a connection
                    
                    // zero usually means sleep forever, but CountDownLatch doesn't interpret it that way.
                    if(timeout == 0)
                        timeout = Integer.MAX_VALUE;
                    
                    if(!awaitLatch.await(timeout, tunit))
                        throw new ConnectionPoolTimeoutException();
                    
                    return new ClientConnAdapterMockup();
                }
            };
        }

        public HttpParams getParams() {
            throw new UnsupportedOperationException("just a mockup");
        }

        public SchemeRegistry getSchemeRegistry() {
            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", new SocketFactoryMockup(null), 80));
            return registry;
        }

        public void releaseConnection(ManagedClientConnection conn) {
            throw new UnsupportedOperationException("just a mockup");
        }

        public void shutdown() {
            throw new UnsupportedOperationException("just a mockup");
        }
    }
    
    private static class CustomGet extends HttpGet {
        private final CountDownLatch releaseTriggerLatch;

        public CustomGet(String uri, CountDownLatch releaseTriggerLatch) throws URISyntaxException {
            super(uri);
            this.releaseTriggerLatch = releaseTriggerLatch;
        }
        
        @Override
        public void setReleaseTrigger(ConnectionReleaseTrigger releaseTrigger) throws IOException {
            try {
                if(!releaseTriggerLatch.await(1, TimeUnit.SECONDS))
                    throw new RuntimeException("Waited too long...");
            } catch(InterruptedException ie) {
                throw new RuntimeException(ie);
            }
            
            super.setReleaseTrigger(releaseTrigger);
        }
        
    }
    
}
