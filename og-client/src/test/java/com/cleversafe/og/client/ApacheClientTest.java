//
// Copyright (C) 2005-2011 Cleversafe, Inc. All rights reserved.
//
// Contact Information:
// Cleversafe, Inc.
// 222 South Riverside Plaza
// Suite 1700
// Chicago, IL 60606, USA
//
// licensing@cleversafe.com
//
// END-OF-HEADER
//
// -----------------------
// @author: rveitch
//
// Date: Jul 7, 2014
// ---------------------

package com.cleversafe.og.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.cleversafe.og.http.HttpRequest;
import com.cleversafe.og.http.auth.BasicAuth;
import com.cleversafe.og.operation.EntityType;
import com.cleversafe.og.operation.Metadata;
import com.cleversafe.og.operation.Method;
import com.cleversafe.og.operation.Request;
import com.cleversafe.og.operation.Response;
import com.cleversafe.og.util.Entities;
import com.cleversafe.og.util.consumer.ByteBufferConsumer;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.base.Function;

public class ApacheClientTest
{
   @Rule
   public final WireMockRule wireMockRule = new WireMockRule();
   private Function<String, ByteBufferConsumer> byteBufferConsumers;
   private Client client;
   private URI objectUri;
   private URI delayedUri;

   // TODO @Mock annotation?
   @SuppressWarnings("unchecked")
   @Before()
   public void setupBefore() throws URISyntaxException
   {
      this.byteBufferConsumers = mock(Function.class);
      final ByteBufferConsumer mockConsumer = mock(ByteBufferConsumer.class);
      final Map<String, String> map = Collections.emptyMap();
      when(mockConsumer.metadata()).thenReturn(map.entrySet().iterator());
      when(this.byteBufferConsumers.apply(any(String.class))).thenReturn(mockConsumer);
      this.client = new ApacheClient.Builder(this.byteBufferConsumers).build();

      // write
      stubFor(put(urlMatching("/container/.*")).willReturn(
            aResponse().withStatus(200)
            ));

      // read
      stubFor(get(urlMatching("/container/.*")).willReturn(
            aResponse().withStatus(200)
                  .withBody(new byte[1024])
            ));

      // delete
      stubFor(delete(urlMatching("/container/.*")).willReturn(
            aResponse().withStatus(204)
            ));

      // 5 second delay
      stubFor(get(urlEqualTo("/delayed")).willReturn(
            aResponse().withStatus(200).withFixedDelay(5000)
            ));

      this.objectUri = new URI("http://127.0.0.1:8080/container/object");
      this.delayedUri = new URI("http://127.0.0.1:8080/delayed");
   }

   @Test(expected = NullPointerException.class)
   public void testNullByteBufferConsumers()
   {
      new ApacheClient.Builder(null).build();
   }

   @Test(expected = IllegalArgumentException.class)
   public void testNegativeConnectTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withConnectTimeout(-1).build();
   }

   @Test
   public void testZeroConnectTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withConnectTimeout(0).build();
   }

   @Test
   public void testPositiveConnectTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withConnectTimeout(1).build();
   }

   @Test(expected = IllegalArgumentException.class)
   public void testNegativeSoTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoTimeout(-1).build();
   }

   @Test
   public void testZeroSoTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoTimeout(0).build();
   }

   @Test
   public void testPositiveSoTimeout()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoTimeout(1).build();
   }

   // TODO verify reuse address?
   @Test
   public void testSoReuseAddress()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingSoReuseAddress(true).build();
   }

   @Test
   public void testNoSoReuseAddress()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingSoReuseAddress(false).build();
   }

   @Test(expected = IllegalArgumentException.class)
   public void testNegativeSoLinger()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoLinger(-2).build();
   }

   @Test
   public void testNegativeSoLinger2()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoLinger(-1).build();
   }

   @Test
   public void testZeroSoLinger()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoLinger(0).build();
   }

   @Test
   public void testPositiveSoLinger()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withSoLinger(1).build();
   }

   // TODO verify so keepalive?
   @Test
   public void testSoKeepAlive()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingSoKeepAlive(true).build();
   }

   @Test
   public void testNoSoKeepAlive()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingSoKeepAlive(false).build();
   }

   // TODO verify tcp nodelay?
   @Test
   public void testTcpNoDelay()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingTcpNoDelay(true).build();
   }

   @Test
   public void testNoTcpNoDelay()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).usingTcpNoDelay(false).build();
   }

   @Test(expected = IllegalArgumentException.class)
   public void testNegativeWaitForContinue()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withWaitForContinue(-1).build();
   }

   @Test(expected = IllegalArgumentException.class)
   public void testZeroWaitForContinue()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withWaitForContinue(0).build();
   }

   @Test
   public void testPositiveWaitForContinue()
   {
      new ApacheClient.Builder(this.byteBufferConsumers).withWaitForContinue(1).build();
   }

   @Test
   public void testPutRequestNoneEntity() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri).build();
      final Response response = this.client.execute(request).get();
      Assert.assertEquals(200, response.getStatusCode());
      Assert.assertEquals(EntityType.NONE, response.getEntity().getType());
      Assert.assertEquals(0, response.getEntity().getSize());
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath())).withRequestBody(equalTo("")));
   }

   // TODO test SOH write at integration test level, using real byteBufferConsumer
   @Test
   public void testPutRequestWithEntity() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withEntity(Entities.of(EntityType.ZEROES, 1024))
            .build();
      final Response response = this.client.execute(request).get();
      Assert.assertEquals(200, response.getStatusCode());
      Assert.assertEquals(EntityType.NONE, response.getEntity().getType());
      Assert.assertEquals(0, response.getEntity().getSize());
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withRequestBody(equalTo(new String(new byte[1024]))));
   }

   @Test
   public void testGetRequest() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri).build();
      final Response response = this.client.execute(request).get();
      Assert.assertEquals(200, response.getStatusCode());
      Assert.assertEquals(EntityType.ZEROES, response.getEntity().getType());
      Assert.assertEquals(1024, response.getEntity().getSize());
      verify(getRequestedFor(urlEqualTo(this.objectUri.getPath())));
   }

   @Test
   public void testDeleteRequest() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.DELETE, this.objectUri).build();
      final Response response = this.client.execute(request).get();
      Assert.assertEquals(204, response.getStatusCode());
      Assert.assertEquals(EntityType.NONE, response.getEntity().getType());
      Assert.assertEquals(0, response.getEntity().getSize());
      verify(deleteRequestedFor(urlEqualTo(this.objectUri.getPath())));
   }

   @Test
   public void testRequestHeaders() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withHeader("key", "value")
            .withHeader("key2", "value2")
            .build();
      this.client.execute(request).get();
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("key", equalTo("value"))
            .withHeader("key2", equalTo("value2")));
   }

   @Test
   public void testContentLength() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .usingChunkedEncoding(false)
            .build();
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withEntity(Entities.of(EntityType.ZEROES, 2048))
            .build();
      client.execute(request).get();
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("Content-Length", equalTo("2048"))
            .withoutHeader("Transfer-Encoding"));
   }

   @Test
   public void testChunkedEncoding() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .usingChunkedEncoding(true)
            .build();
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withEntity(Entities.of(EntityType.ZEROES, 2048))
            .build();
      client.execute(request).get();
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("Transfer-Encoding", equalTo("chunked"))
            .withoutHeader("Content-Length"));
   }

   @Test
   public void testExpect100Continue() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .usingExpectContinue(true)
            .build();
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withEntity(Entities.of(EntityType.ZEROES, 2048))
            .build();
      client.execute(request).get();
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("Expect", equalTo("100-continue")));
   }

   @Test
   public void testNoExpect100Continue() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .usingExpectContinue(false)
            .build();
      final Request request = new HttpRequest.Builder(Method.PUT, this.objectUri)
            .withEntity(Entities.of(EntityType.ZEROES, 2048))
            .build();
      client.execute(request).get();
      verify(putRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withoutHeader("Expect"));
   }

   @Test
   public void testAuthentication() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .withAuthentication(new BasicAuth())
            .build();
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri)
            .withMetadata(Metadata.USERNAME, "test")
            .withMetadata(Metadata.PASSWORD, "test")
            .build();
      client.execute(request).get();
      verify(getRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("Authorization", matching("Basic .*")));
   }

   @Test
   public void testNoAuthentication() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri).build();
      this.client.execute(request).get();
      verify(getRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withoutHeader("Authorization"));
   }

   @Test
   public void testUserAgent() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .withUserAgent("testUserAgent")
            .build();
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri).build();
      client.execute(request).get();
      verify(getRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("User-Agent", equalTo("testUserAgent")));
   }

   @Test
   public void testNoUserAgent() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri).build();
      this.client.execute(request).get();
      verify(getRequestedFor(urlEqualTo(this.objectUri.getPath()))
            .withHeader("User-Agent", matching("Apache.*")));
   }

   @Test
   public void testSoTimeoutExceeded() throws InterruptedException, ExecutionException
   {
      final Client client = new ApacheClient.Builder(this.byteBufferConsumers)
            .withSoTimeout(5)
            .build();
      final Request request =
            new HttpRequest.Builder(Method.GET, this.delayedUri).build();
      final Response response = client.execute(request).get();
      Assert.assertEquals(499, response.getStatusCode());
      Assert.assertNotNull(response.getMetadata(Metadata.ABORTED));
   }

   @Test
   public void testRequestIdMetadata() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.objectUri)
            .withMetadata(Metadata.REQUEST_ID, "objectName")
            .build();
      final Response response = this.client.execute(request).get();
      Assert.assertEquals("objectName", response.getMetadata(Metadata.REQUEST_ID));
   }

   @Test
   public void testImmediateShutdown() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.delayedUri).build();
      this.client.execute(request);
      final long start = System.nanoTime();
      this.client.shutdown(true).get();
      final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      // immediate shutdown takes less than 1 second
      Assert.assertTrue(duration < 1000);
   }

   @Test
   public void testGracefulShutdown() throws InterruptedException, ExecutionException
   {
      final Request request = new HttpRequest.Builder(Method.GET, this.delayedUri).build();
      this.client.execute(request);
      final long start = System.nanoTime();
      this.client.shutdown(false).get();
      final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      // graceful shutdown takes at least request time
      Assert.assertTrue(duration >= 5000);
   }
}