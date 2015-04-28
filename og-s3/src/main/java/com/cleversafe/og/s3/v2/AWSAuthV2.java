/*
 * Copyright (C) 2005-2015 Cleversafe, Inc. All rights reserved.
 * 
 * Contact Information: Cleversafe, Inc. 222 South Riverside Plaza Suite 1700 Chicago, IL 60606, USA
 * 
 * licensing@cleversafe.com
 */

package com.cleversafe.og.s3.v2;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.cleversafe.og.api.Request;
import com.cleversafe.og.http.Headers;
import com.cleversafe.og.http.HttpAuth;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;

/**
 * An http auth implementation that creates authorization header values using the awsv2 auth
 * algorithm
 * <p>
 * Note: this implementation includes the following limitations:
 * <ul>
 * <li>Only path-style requests are supported. Virtual hosts are not supported</li>
 * <li>Request canonicalization step 4 (other query params) is not supported</li>
 * <li>Amz header canonicalization steps 3 and 4 are not supported</li>
 * </ul>
 * <p>
 * Additionally, although the documentation does not specify it, the examples indicate that the
 * x-amz-date header should be ignored when computing canonicalized amz headers. This implementation
 * chooses to follow this derived behavior
 * 
 * @see <a href="http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html"
 *      target="_blank">Amazon REST Authentication</a>
 * @since 1.0
 */
public class AWSAuthV2 implements HttpAuth {
  private static final String HMAC_SHA1 = "HmacSHA1";
  private static final Splitter QUERY_SPLITTER = Splitter.on("&");
  private static final Splitter PARAM_SPLITTER = Splitter.on("=");
  private static final List<String> SUBRESOURCES = ImmutableList.of("acl", "lifecycle", "location",
      "logging", "notification", "partNumber", "policy", "requestPayment", "torrent", "uploadId",
      "uploads", "versionId", "versioning", "versions", "website");

  protected String authenticate(final Request request, final String awsAccessKeyId,
      final String awsSecretAccessKey) {
    return new StringBuilder().append("AWS ").append(awsAccessKeyId).append(":")
        .append(signature(request, awsSecretAccessKey)).toString();
  }

  private String signature(final Request request, final String awsSecretAccessKey) {
    return BaseEncoding.base64().encode(
        hmacSha1(awsSecretAccessKey, stringToSign(request).getBytes(Charsets.UTF_8)));
  }

  public byte[] hmacSha1(final String awsSecretAccessKey, final byte[] data) {
    final SecretKeySpec signingKey =
        new SecretKeySpec(awsSecretAccessKey.getBytes(Charsets.UTF_8), HMAC_SHA1);
    Mac mac;
    try {
      mac = Mac.getInstance(HMAC_SHA1);
      mac.init(signingKey);
      return mac.doFinal(data);
    } catch (final Exception e) {
      // Wrapping checked algorithm and signing exceptions as unchecked exception in order to
      // fail fast without adding these exceptions to HttpAuth interface
      throw new RuntimeException("Exception signing request (awsv2)", e);
    }
  }

  public String stringToSign(final Request request) {
    return new StringBuilder().append(request.getMethod()).append("\n")
        .append(getHeader(request, "Content-MD5", "")).append("\n")
        .append(getHeader(request, "Content-Type", "")).append("\n")
        .append(getHeader(request, "X-Amz-Date", getHeader(request, "Date", ""))).append("\n")
        .append(canonicalizedAmzHeaders(request)).append(canonicalizedResource(request)).toString();
  }

  // lookup by key and lower(key)
  private String getHeader(final Request request, final String key, final String defaultValue) {
    String value = request.headers().get(key);
    if (value != null)
      return value;

    value = request.headers().get(key.toLowerCase(Locale.US));
    if (value != null)
      return value;

    return defaultValue;
  }

  public String canonicalizedAmzHeaders(final Request request) {
    // create canonicalHeaders lazily, usually no x-amz- headers will be present
    SortedMap<String, String> canonicalHeaders = null;
    for (final Entry<String, String> header : request.headers().entrySet()) {
      final String keyLower = header.getKey().trim().toLowerCase(Locale.US);
      // ignoring x-amz-date, is this correct?
      if (keyLower.startsWith("x-amz-") && !"x-amz-date".equals(keyLower)) {
        if (canonicalHeaders == null)
          canonicalHeaders = Maps.newTreeMap();

        canonicalHeaders.put(keyLower, header.getValue().trim());
      }
    }
    final StringBuilder s = new StringBuilder();
    if (canonicalHeaders != null) {
      for (final Entry<String, String> header : canonicalHeaders.entrySet()) {
        s.append(header.getKey()).append(":").append(header.getValue()).append("\n");
      }
    }
    return s.toString();
  }

  public String canonicalizedResource(final Request request) {
    // create subresources lazily, usually no subresources will be present
    SortedMap<String, String> subresources = null;

    for (final Entry<String, String> q : splitQueryParameters(request.getUri().getQuery())
        .entrySet()) {
      if (SUBRESOURCES.contains(q.getKey())) {
        if (subresources == null)
          subresources = Maps.newTreeMap();

        subresources.put(q.getKey(), q.getValue());
      }
    }

    if (subresources != null) {
      return new StringBuilder().append(request.getUri().getPath()).append("?")
          .append(joinQueryParameters(subresources)).toString();
    }

    return request.getUri().getPath();
  }

  // Guava includes a map splitter, however it requires that all keys also have values, which is
  // not always the case for aws signing e.g. torrent, so this method is required
  public Map<String, String> splitQueryParameters(final String query) {
    // short circuit common case where there are no query parameters
    if (query == null || query.length() == 0)
      return ImmutableMap.of();

    final Map<String, String> queryParameters = Maps.newHashMap();

    for (final String q : QUERY_SPLITTER.split(query)) {
      final Iterator<String> it = PARAM_SPLITTER.split(q).iterator();
      final String key = it.next();
      String value = null;
      if (it.hasNext())
        value = it.next();

      queryParameters.put(key, value);
    }

    return queryParameters;
  }

  // Guava includes a map joiner, however it requires that all keys also have values, which is
  // not always the case for aws signing e.g. torrent, so this method is required
  public String joinQueryParameters(final Map<String, String> queryParameters) {
    // short circuit common case where there are no query parameters
    if (queryParameters == null || queryParameters.size() == 0)
      return "";

    final StringBuilder s = new StringBuilder();
    final Iterator<Entry<String, String>> it = queryParameters.entrySet().iterator();
    appendQueryParam(s, it.next());

    while (it.hasNext()) {
      s.append("&");
      appendQueryParam(s, it.next());
    }

    return s.toString();
  }

  private void appendQueryParam(final StringBuilder s, final Entry<String, String> queryParam) {
    s.append(queryParam.getKey());
    if (queryParam.getValue() != null)
      s.append("=").append(queryParam.getValue());
  }

  @Override
  public String toString() {
    return "AWSAuthV2 []";
  }

  @Override
  public Map<String, String> getAuthorizationHeaders(Request request) {
    final String awsAccessKeyId = checkNotNull(request.headers().get(Headers.X_OG_USERNAME));
    final String awsSecretAccessKey = checkNotNull(request.headers().get(Headers.X_OG_PASSWORD));
    return Collections.singletonMap(HttpHeaders.AUTHORIZATION,
        authenticate(request, awsAccessKeyId, awsSecretAccessKey));
  }

  @Override
  public InputStream wrapStream(Request request, InputStream stream) {
    return stream;
  }

  @Override
  public long getContentLength(final Request request) {
    return request.getBody().getSize();
  }
}
