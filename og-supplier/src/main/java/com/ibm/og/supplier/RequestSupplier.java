/* Copyright (c) IBM Corporation 2016. All Rights Reserved.
 * Project name: Object Generator
 * This project is licensed under the Apache License 2.0, see LICENSE.
 */

package com.ibm.og.supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.io.BaseEncoding;
import com.ibm.og.api.Body;
import com.ibm.og.api.Method;
import com.ibm.og.api.Operation;
import com.ibm.og.api.Request;
import com.ibm.og.http.MD5DigestLoader;
import com.ibm.og.http.Credential;
import com.ibm.og.http.HttpRequest;
import com.ibm.og.http.Scheme;
import com.ibm.og.object.RandomObjectPopulator;
import com.ibm.og.util.Context;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supplier of requests
 * 
 * @since 1.0
 */
public class RequestSupplier implements Supplier<Request> {
  private static final Logger _logger = LoggerFactory.getLogger(RequestSupplier.class);
  private static final Joiner.MapJoiner PARAM_JOINER = Joiner.on('&').withKeyValueSeparator("=").useForNull("");
  private final Function<Map<String, String>, String> id;
  private final Method method;
  private final Scheme scheme;
  private final Function<Map<String, String>, String> host;
  private final Integer port;
  private final String uriRoot;
  private final Function<Map<String, String>, String> container;
  private final String apiVersion;
  private final Function<Map<String, String>, String> object;
  private final Map<String, Function<Map<String, String>, String>> queryParameters;
  private final boolean trailingSlash;
  private final Map<String, Function<Map<String, String>, String>> headers;
  private final List<Function<Map<String, String>, String>> context;
  private final List<Function<Map<String, String>, String>> sseSourceContext;
  private final Function<Map<String, String>, Credential> credentials;
  private final Function<Map<String, String>, Body> body;
  private final boolean virtualHost;
  private final Function<Map<String, String>, Long> retention;
  private final Supplier<Function<Map<String, String>, String>> legalHold;
  private final Operation operation;
  private final boolean contentMd5;
  private final LoadingCache<Long, byte[]> md5ContentCache;



  /**
   * Creates an instance
   * 
   * @param id a supplier of ids to uniquely identify each request that is generated by this
   *        instance
   * @param method
   * @param scheme
   * @param host
   * @param port
   * @param uriRoot the base url part e.g. /soh/, /, /s3/
   * @param container
   * @param object
   * @param queryParameters static query parameters to all requests
   * @param trailingSlash whether or not to add a trailing slash to the url
   * @param headers headers to add to each request; header values may be dynamic
   * @param context request metadata to be sent with the created request
   * @param credentials username/password or keystone token
   * @param body a description of the request body to add to the request
   */
  // FIXME refactor username, password, and keystoneToken so they are embedded in headers rather
  // than separate fields
  public RequestSupplier(final Operation operation, final Function<Map<String, String>, String> id,
      final Method method, final Scheme scheme, final Function<Map<String, String>, String> host,
      final Integer port, final String uriRoot, final Function<Map<String, String>, String> container,
      final String apiVersion,
      final Function<Map<String, String>, String> object,
      final Map<String, Function<Map<String, String>, String>> queryParameters,
      final boolean trailingSlash, final Map<String, Function<Map<String, String>, String>> headers,
      final List<Function<Map<String, String>, String>> context,
      final List<Function<Map<String, String>, String>> sseSourceContext,
      final Function<Map<String, String>, Credential> credentials,
      final Function<Map<String, String>, Body> body, final boolean virtualHost,
      final Function<Map<String, String>, Long> retention, final Supplier<Function<Map<String, String>, String>> legalHold,
      final boolean contentMd5) {

    this.id = id;
    this.method = checkNotNull(method);
    this.scheme = checkNotNull(scheme);
    this.host = checkNotNull(host);
    this.port = port;
    this.uriRoot = uriRoot;
    this.container = container;
    this.apiVersion = apiVersion;
    this.object = object;
    this.queryParameters = ImmutableMap.copyOf(queryParameters);
    this.trailingSlash = trailingSlash;
    this.headers = ImmutableMap.copyOf(headers);
    this.context = ImmutableList.copyOf(context);
    this.sseSourceContext = sseSourceContext;
    this.credentials = credentials;
    this.body = body;
    this.virtualHost = virtualHost;
    this.operation = operation;
    this.retention = retention;
    this.legalHold = legalHold;
    this.contentMd5 = contentMd5;
    this.md5ContentCache = CacheBuilder.newBuilder().maximumSize(100).build(new MD5DigestLoader());

    checkArgument(!(this.container == null && this.object != null));
  }

  @Override
  public Request get() {
    final Map<String, String> requestContext = Maps.newHashMap();

    // populate the context map with any relevant metadata for this request
    for (final Function<Map<String, String>, String> function : this.context) {
      // return value for context functions is ignored
      function.apply(requestContext);
    }

    if (this.container != null) {
      // container-name is populated in the context now if it is available
      // populate container name in context because Credential needs that to lookup
      // storage account
      // todo: Fix me. need to refactor this and handle ordering in the context
      this.container.apply(requestContext);

    }
    if (this.credentials != null) {
      Credential credential = this.credentials.apply(requestContext);
      String username = credential.getUsername();
      String password = credential.getPassword();
      String keystoneToken = credential.getKeystoneToken();
      String storageAccountName = credential.getStorageAccountName();

      if(username != null)
        requestContext.put(Context.X_OG_USERNAME, username);
      if(password != null)
        requestContext.put(Context.X_OG_PASSWORD, password);
      if(keystoneToken != null)
        requestContext.put(Context.X_OG_KEYSTONE_TOKEN, keystoneToken);
      if(storageAccountName != null) {
        requestContext.put(Context.X_OG_STORAGE_ACCOUNT_NAME, storageAccountName);
      }

    }
    // populate the context map with any relevant metadata for this request
    if (this.sseSourceContext != null) {
      for (final Function<Map<String, String>, String> function : this.sseSourceContext) {
        // return value for context functions is ignored
        function.apply(requestContext);
      }
    }

    Function<Map<String, String>, String> legalholdFunction;
    if (this.legalHold != null) {
      legalholdFunction = this.legalHold.get();
      if (legalholdFunction != null) {
        legalholdFunction.apply(requestContext);
      }
    }

    final HttpRequest.Builder builder =
        new HttpRequest.Builder(this.method, getUrl(requestContext), this.operation);

    for (final Map.Entry<String, Function<Map<String, String>, String>> header : this.headers
        .entrySet()) {
      builder.withHeader(header.getKey(), header.getValue().apply(requestContext));
    }

    if (this.retention != null) {
      this.retention.apply(requestContext);
      if (requestContext.get(Context.X_OG_OBJECT_RETENTION) != null) {
        builder.withHeader(Context.X_OG_OBJECT_RETENTION, requestContext.get(Context.X_OG_OBJECT_RETENTION));
      }
    }

    if (requestContext.get(Context.X_OG_LEGAL_HOLD) != null) {
      builder.withHeader(Context.X_OG_LEGAL_HOLD, requestContext.get(Context.X_OG_LEGAL_HOLD));
    }

    if (this.id != null) {
      builder.withContext(Context.X_OG_REQUEST_ID, this.id.apply(requestContext));
    }

    for (final Map.Entry<String, String> entry : requestContext.entrySet()) {
      builder.withContext(entry.getKey(), entry.getValue());
    }


    if (this.body != null) {
      Body body = this.body.apply(requestContext);
      builder.withBody(body);
      if (this.retention != null || this.legalHold != null || this.contentMd5) {
        try {
          Long size = body.getSize();
          byte[] md5 = md5ContentCache.get(size);
          builder.withHeader(Context.X_OG_CONTENT_MD5, BaseEncoding.base64().encode(md5));
        } catch (Exception e) {
            _logger.error(e.getMessage());
        }
      }
    }



    if (this.queryParameters != null) {
      for (final Map.Entry<String, Function<Map<String, String>, String>> queryParams : this.queryParameters
          .entrySet()) {
        builder.withQueryParameter(queryParams.getKey(), queryParams.getValue().apply(requestContext));
      }
    }

    return builder.build();
  }

  private URI getUrl(final Map<String, String> context) {

    final StringBuilder s = new StringBuilder().append(this.scheme).append("://");
    appendHost(s, context);
    appendPort(s);
    appendPath(s, context, apiVersion);
    appendTrailingSlash(s);
    appendQueryParams(s, context);

    try {
      return new URI(s.toString());
    } catch (final URISyntaxException e) {
      // Wrapping checked exception as unchecked because most callers will not be able to handle
      // it and I don't want to include URISyntaxException in the entire signature chain
      throw new IllegalArgumentException(e);
    }
  }

  private void appendHost(final StringBuilder s, final Map<String, String> context) {
    if (this.virtualHost) {
      String containerName = context.get(Context.X_OG_CONTAINER_NAME);
      if (containerName != null) {
        s.append(containerName).append(".");
      }
    }

    s.append(this.host.apply(context));
  }

  private void appendPort(final StringBuilder s) {
    if (this.port != null) {
      s.append(":").append(this.port);
    }
  }

  private void appendPath(final StringBuilder s, final Map<String, String> context, final String apiVersion) {
    if (!this.virtualHost) {
      s.append("/");
      if (this.uriRoot != null) {
        s.append(this.uriRoot).append("/");
      }

      if (apiVersion != null) {
        s.append(apiVersion).append("/");
      }
      String storageAccount = getStorageAccountPath(context, apiVersion);
      if (storageAccount != null) {
        s.append(getStorageAccountPath(context, apiVersion));
      }

      String containerName = context.get(Context.X_OG_CONTAINER_NAME);
      if (containerName != null) {
        s.append(containerName);
      }
    }

    if (this.object != null) {
      s.append("/").append(this.object.apply(context));
    }
  }

  private void appendTrailingSlash(final StringBuilder s) {
    if (this.trailingSlash) {
      s.append("/");
    }
  }

  private void appendQueryParams(final StringBuilder s, final Map<String, String> context) {
    final Map<String, String> queryParamsMap = Maps.newHashMap();

    StringBuilder sb = new StringBuilder();
    int mapSize = this.queryParameters.size();
    int counter = 0;
    for (final Map.Entry<String, Function<Map<String, String>, String>> queryParams : this.queryParameters
        .entrySet()) {
      counter++;
      queryParamsMap.put(queryParams.getKey(), queryParams.getValue().apply(context));
      sb.append(queryParams.getKey());
      String value = queryParams.getValue().apply(context);
      if (value != null) {
        sb.append("=").append(value);
      }
      if (counter < mapSize) {
        sb.append("&");
      }
    }

//    final String queryParams = PARAM_JOINER.join(queryParamsMap);
//    if (queryParams.length() > 0) {
//      s.append("?").append(queryParams);
//
    if (sb.toString().length() != 0) {
      s.append("?").append(sb.toString());
    }
  }

  private String getStorageAccountPath(final Map<String, String> context, final String apiVersion) {
    String storageAccountName = context.get(Context.X_OG_STORAGE_ACCOUNT_NAME);
    StringBuilder s = new StringBuilder();
    if(storageAccountName != null) {
      s.append(storageAccountName).append("/");
    } else if (apiVersion != null && storageAccountName == null) {
      // FIXME - this is a case to accomodate vault mode swift account. If the api version is present,
      // the dsnet expects a storage account name. so pass a dummy account name when there is no authentication
      s.append("dummyaccount").append("/");
    } else {
      return null;
    }
    return s.toString();
  }

  @Override
  public String toString() {
    return String.format(
        "RequestSupplier [%n" + "method=%s,%n" + "scheme=%s,%n" + "host=%s,%n" + "port=%s,%n"
            + "uriRoot=%s,%n" + "container=%s,%n" + "object=%s,%n" + "queryParameters=%s,%n"
            + "trailingSlash=%s,%n" + "headers=%s,%n" + "body=%s%n" + "]",
        this.method, this.scheme, this.host, this.port, this.uriRoot, this.container, this.object,
        this.queryParameters, this.trailingSlash, this.headers, this.body);
  }
}
