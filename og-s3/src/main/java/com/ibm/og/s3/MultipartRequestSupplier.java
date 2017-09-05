/* Copyright (c) IBM Corporation 2016. All Rights Reserved.
 * Project name: Object Generator
 * This project is licensed under the Apache License 2.0, see LICENSE.
 */

package com.ibm.og.s3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.io.BaseEncoding;
import com.ibm.og.api.Body;
import com.ibm.og.api.DataType;
import com.ibm.og.api.Method;
import com.ibm.og.api.Operation;
import com.ibm.og.api.Request;
import com.ibm.og.api.Response;
import com.ibm.og.http.Bodies;
import com.ibm.og.http.Credential;
import com.ibm.og.http.HttpRequest;
import com.ibm.og.http.MD5DigestLoader;
import com.ibm.og.http.Scheme;
import com.ibm.og.util.Context;
import com.ibm.og.util.Pair;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A supplier of multipart requests
 * 
 * @since 1.0
 */
public class MultipartRequestSupplier implements Supplier<Request> {
  private static final Logger _logger = LoggerFactory.getLogger(MultipartRequestSupplier.class);

  private static final Joiner.MapJoiner PARAM_JOINER = Joiner.on('&').withKeyValueSeparator("=");
  private final Function<Map<String, String>, String> id;
  private final Scheme scheme;
  private final Function<Map<String, String>, String> host;
  private final Integer port;
  private final String uriRoot;
  private final Function<Map<String, String>, String> container;
  private final Function<Map<String, String>, String> object;
  private final Function<Map<String, String>, Long> partSize;
  private final Function<Map<String, String>, Integer> partsPerSession;
  private final int targetSessions;
  private final Map<String, Function<Map<String, String>, String>> queryParameters;
  private final boolean trailingSlash;
  private final Map<String, Function<Map<String, String>, String>> headers;
  private final List<Function<Map<String, String>, String>> context;
  private final Function<Map<String, String>, Credential> credentials;
  private final Function<Map<String, String>, Body> body;
  private final boolean virtualHost;
  private final boolean contentMd5;
  private final LoadingCache<Long, byte[]> md5ContentCache;


  // constants
  private final int NO_PART = -1;
  private final String UPLOAD_ID = "uploadId";
  private final String PART_NUMBER = "partNumber";
  private final String UPLOADS = "uploads";

  private final Random randomNumber;
  // request lists and HashMap
  private final Map<String, MultipartInfo> multipartRequestMap;
  private final List<MultipartInfo> actionableMultipartSessions;

  private final MPSessionManager sessionManager;
  /**
   * Creates an instance
   *
   * @param id a supplier of ids to uniquely identify each request that is generated by this
   *        instance
   * @param scheme protocol scheme
   * @param host accesser ip
   * @param port accesser port
   * @param uriRoot the base url part e.g. /soh/, /, /s3/
   * @param container container
   * @param object object to be transported
   * @param queryParameters static query parameters to all requests
   * @param trailingSlash whether or not to add a trailing slash to the url
   * @param headers headers to add to each request; header values may be dynamic
   * @param context request metadata to be sent with the created request
   * @param credentials username/password or keystone token
   * @param body a description of the request body to add to the request
   */
  // FIXME refactor username, password, and keystoneToken so they are embedded in headers rather
  // than separate fields
  public MultipartRequestSupplier(final Function<Map<String, String>, String> id,
      final Scheme scheme, final Function<Map<String, String>, String> host,
      final Integer port, final String uriRoot,
      final Function<Map<String, String>, String> container,
      final Function<Map<String, String>, String> object,
      final Function<Map<String, String>, Long> partSize,
      final Function<Map<String, String>, Integer> partsPerSession,
      final int targetSessions,
      final Map<String, Function<Map<String, String>, String>> queryParameters,
      final boolean trailingSlash, final Map<String, Function<Map<String, String>, String>> headers,
      final List<Function<Map<String, String>, String>> context,
      final Function<Map<String, String>, Credential> credentials,
      final Function<Map<String, String>, Body> body, final boolean virtualHost,
      final boolean contentMd5) {

    this.id = id;
    this.scheme = checkNotNull(scheme);
    this.host = checkNotNull(host);
    this.port = port;
    this.uriRoot = uriRoot;
    this.container = container;
    this.object = object;
    this.partSize = partSize;
    this.partsPerSession = partsPerSession;
    this.targetSessions = targetSessions;
    this.queryParameters = ImmutableMap.copyOf(queryParameters);
    this.trailingSlash = trailingSlash;
    this.headers = ImmutableMap.copyOf(headers);
    this.context = ImmutableList.copyOf(context);
    this.credentials = credentials;
    this.body = body;
    this.virtualHost = virtualHost;
    this.contentMd5 = contentMd5;
    this.randomNumber = new Random();
    this.actionableMultipartSessions = Collections.synchronizedList(new ArrayList<MultipartInfo>());
    this.multipartRequestMap = new ConcurrentHashMap<String, MultipartInfo>();
    this.sessionManager = new MPSessionManager();
    this.md5ContentCache = CacheBuilder.newBuilder().maximumSize(100).build(new MD5DigestLoader());

  }

  private enum MultipartRequest {
    INITIATE, PART, COMPLETE, ABORT, INTERNAL_PENDING, INTERNAL_DONE, INTERNAL_ERROR
  }

  private class PartInfo {
    final String partNumber;
    final String partId;

    public PartInfo(String partNumber, String partId) {
      this.partNumber = partNumber;
      this.partId = partId;
    }
  }

  /**
   *  A Multipart session is a single session for transferring object in multiple parts.
   *  MPSessionManager manages the sessions.
   */
  private class MPSessionManager {

    AtomicInteger inProgressSessions = new AtomicInteger(0);
    Lock sessionsLock = new ReentrantLock();
    Condition sessionAvailable = sessionsLock.newCondition();

    public MultipartInfo getNextSession() {
      boolean done = false;
      MultipartInfo session = null;
      sessionsLock.lock();

        try {
          while (!done) {
            if (this.inProgressSessions.get() < targetSessions) {
              // return null to start a new session
              session = null;
              done = true;
            } else if (actionableMultipartSessions.size() > 0) {
              // pick random session
              session = getActiveMultipartOperation();
              if (session.getNextMultipartRequest() == MultipartRequest.INTERNAL_PENDING ||
                      session.getNextMultipartRequest() == MultipartRequest.INTERNAL_ERROR) {
                actionableMultipartSessions.remove(session);
                session.setInActionableSessions(false);
                _logger.debug("Removed active Multipart session. count now is [{}]", actionableMultipartSessions.size());
                continue;
              }
              if (session.getNextMultipartRequest() == MultipartRequest.COMPLETE) {
                // if it is a complete request remove the session from the actionable list
                actionableMultipartSessions.remove(session);
                session.setInActionableSessions(false);
                _logger.debug("Removed active Multipart session. count now is [{}]", actionableMultipartSessions.size());
              }
              done = true;
            } else {
              try {
                sessionAvailable.await();
              } catch(InterruptedException ie) {
                _logger.info("MultipartRequestSupplier thread interrupted while getting request");
                done = true;
              }
            }
          }
        }finally {
          sessionsLock.unlock();
        }
      return session;
    }

    public HttpRequest.Builder getNextRequest(final Map<String, String> requestContext) {

      HttpRequest.Builder builder = null;
      MultipartInfo session = getNextSession();
      if (session == null) {
        // create a new session
        this.inProgressSessions.getAndIncrement();
        // populate the context map with any relevant metadata for this request
        // based on what the current operation is
        for (final Function<Map<String, String>, String> function : context) {
          // return value for context functions is ignored
          function.apply(requestContext);
        }
        // create the initiate request
        builder = createInitiateRequest(requestContext);
        builder.withQueryParameter(UPLOADS, null);
      } else {
        MultipartRequest multipartRequest = session.getNextMultipartRequest();
        switch (multipartRequest) {
          case PART:
            int partNumber = session.startPartRequest();
            builder = createPartRequest(requestContext, partNumber, session.uploadId, session.getNextPartSize(),
                    session.bodyDataType, session.context);
            builder.withQueryParameter(PART_NUMBER, String.valueOf(partNumber));
            builder.withQueryParameter(UPLOAD_ID, session.uploadId);
            break;
          case COMPLETE:
            builder = createCompleteRequest(requestContext, session.uploadId,
                    session.startCompleteRequest(), session.context);
            builder.withQueryParameter(UPLOAD_ID, session.uploadId);
            // remove session from actionable list
            actionableMultipartSessions.remove(session);
            break;
          case INTERNAL_PENDING:
            _logger.error("Not expecting INTERNAL_PENDING while creating a request");
            break;
          case ABORT:
            builder = createAbortRequest(requestContext, session.uploadId, session.context);
            builder.withQueryParameter(UPLOAD_ID, session.uploadId);
            break;
        }
      }
      return builder;
    }
  }

  private class MultipartInfo {
    private final Lock stateLock;
    final String containerName;
    final String containerSuffix;
    final String objectName;
    final String bodyDataType;
    final long objectSize;
    final long partSize;
    final int maxParts;
    final long lastPartSize;
    final String uploadId;
    final Queue<PartInfo> partsInfo;
    final int partRequestsToSend; //Part Requests
    int nextPartNumber;
    int inProgressPartRequests;
    int finishedPartRequests;
    boolean inProgressCompleteRequest;
    boolean finishedCompleteRequest;
    boolean inActionableSessions = false;
    final Map<String, String> context;

    public MultipartInfo(String containerName, String objectName, String uploadId,
        long objectSize, long partSize, int maxParts, String containerSuffix, String bodyDataType,
                         Map<String, String> requestContext) {

      this.stateLock = new ReentrantLock();
      this.containerName = containerName;
      this.containerSuffix = containerSuffix;
      this.bodyDataType = bodyDataType;
      this.objectName = objectName;
      this.objectSize = objectSize;
      this.partSize = partSize; // bytes
      this.maxParts = maxParts;
      this.uploadId = uploadId;
      this.nextPartNumber = 0;
      this.inProgressPartRequests = 0;
      this.finishedPartRequests = 0;
      this.inProgressCompleteRequest = false;
      this.finishedCompleteRequest = false;
      this.context = requestContext;
      this.partsInfo = new PriorityBlockingQueue<PartInfo>(200, new Comparator<PartInfo>() {
        @Override public int compare(PartInfo o1, PartInfo o2) {
          if(Integer.parseInt(o1.partNumber) < Integer.parseInt(o2.partNumber)) {
            return -1;
          } else if (Integer.parseInt(o1.partNumber) > Integer.parseInt(o2.partNumber)) {
            return 1;
          } else {
            return 0;
          }
        }
      });

      int parts = (int)(this.objectSize/this.partSize);

      // not all parts are the same size
      if(0 != (this.objectSize % this.partSize)) {
        this.partRequestsToSend = parts + 1;
        this.lastPartSize = this.objectSize % this.partSize;
        // parts are all the same size
      } else {
        this.partRequestsToSend = parts;
        this.lastPartSize = partSize;
      }
    }

    public MultipartRequest getNextMultipartRequest() {
      // all parts sent, no complete yet, send the complete
      MultipartRequest retVal;
      stateLock.lock();
      try {
        _logger.debug("pts [{}] ipr [{}] fpr [{}] fcR [{}] ipCR [{}] ", this.partRequestsToSend,
                this.inProgressPartRequests, this.finishedPartRequests, this.finishedCompleteRequest,
                this.inProgressCompleteRequest);
        if ((this.inProgressPartRequests == 0) &&
                (!this.finishedCompleteRequest) &&
                (!this.inProgressCompleteRequest) &&
                (this.finishedPartRequests == this.partRequestsToSend)) {
          retVal = MultipartRequest.COMPLETE;
          // all parts sent, complete sent, done
        } else if ((this.finishedPartRequests == this.partRequestsToSend) &&
                (this.finishedCompleteRequest) && (!this.inProgressCompleteRequest)) {
          retVal = MultipartRequest.INTERNAL_DONE;
          // haven't sent all the parts and haven't reached maxParts threshold
        } else if (((this.inProgressPartRequests + this.finishedPartRequests) < this.partRequestsToSend) &&
                (this.inProgressPartRequests < this.maxParts)) {
          retVal = MultipartRequest.PART;
          // all parts sent, but not finished or inProgress parts is at max
        } else if (((this.inProgressPartRequests + this.finishedPartRequests) == this.partRequestsToSend) ||
                (this.inProgressPartRequests >= maxParts)) {
          retVal = MultipartRequest.INTERNAL_PENDING;
        } else {
          retVal = MultipartRequest.INTERNAL_ERROR;
        }
        _logger.debug("next Multipart request is [{}]", retVal);
      } finally {
        stateLock.unlock();
      }
      return retVal;
    }

    public long getNextPartSize() {
      long retVal;
      stateLock.lock();
      try {
        if (this.nextPartNumber < this.partRequestsToSend) {
          retVal = this.partSize;
        } else {
          retVal = this.lastPartSize;
        }
      }
      finally {
        stateLock.unlock();
      }
      return retVal;
    }

    /*
    returns the next partNumber
     */
    public int startPartRequest() {
      int retVal;
      stateLock.lock();
      try {
        this.inProgressPartRequests++;
        this.nextPartNumber++;
        retVal = this.nextPartNumber;
      }
      finally {
        stateLock.unlock();
      }
      return retVal;
    }

    public void finishPartRequest(PartInfo partInfo) {
      this.partsInfo.add(partInfo);

      stateLock.lock();
      try {
        this.inProgressPartRequests--;
        this.finishedPartRequests++;
      }
      finally {
        stateLock.unlock();
      }

    }

    public String startCompleteRequest() {
      stateLock.lock();
      try {
        this.inProgressCompleteRequest = true;
      }
      finally {
        this.stateLock.unlock();
      }
      return generateCompleteRequestBody();
    }

    public void finishCompleteRequest() {
      stateLock.lock();
      try {
        this.finishedCompleteRequest = true;
        this.inProgressCompleteRequest = false;
      }
      finally {
        stateLock.unlock();
      }
    }

    private String generateCompleteRequestBody() {
      String completeMultipartUploadBeginElement = "<CompleteMultipartUpload>";
      String completeMultipartUploadEndElement = "</CompleteMultipartUpload>";
      String partBeginElement = "<Part>";
      String partEndElement = "</Part>";
      String partNumberBeginElement = "<PartNumber>";
      String partNumberEndElement = "</PartNumber>";
      String etagBeginElement = "<ETag>";
      String etagEndElement = "</ETag>";

      PartInfo part;
      StringBuilder sb = new StringBuilder();
      sb.append(completeMultipartUploadBeginElement);

      while(!partsInfo.isEmpty()) {
        part = partsInfo.poll();
        sb.append(partBeginElement).append(partNumberBeginElement).append(part.partNumber).append(partNumberEndElement).append(etagBeginElement)
            .append(part.partId).append(etagEndElement).append(partEndElement);
      }

      sb.append(completeMultipartUploadEndElement);

      return sb.toString();
    }

    public boolean getInActionableSessions() {
      return this.inActionableSessions;
    }

    public void setInActionableSessions(boolean inActionableSessions) {
      this.inActionableSessions = inActionableSessions;
    }
  }

  @Subscribe
  public void update(final Pair<Request, Response> result) {
    Request request = result.getKey();
    Response response = result.getValue();
    Map<String, String> requestContext  = request.getContext();
    Map<String, String> responseContext = response.getContext();
    Map<String, String> responseHeaders = response.headers();

    String multipartrequestOperation = requestContext.get(Context.X_OG_MULTIPART_REQUEST);
    if(multipartrequestOperation == null) {
      // not a multipart operation so just return
      return;
    }
    String requestBodyDataType = requestContext.get(Context.X_OG_MULTIPART_BODY_DATA_TYPE);
    String requestContainerName = requestContext.get(Context.X_OG_MULTIPART_CONTAINER);
    String requestContainerSuffix = requestContext.get(Context.X_OG_CONTAINER_SUFFIX);
    String requestObjectName = requestContext.get(Context.X_OG_OBJECT_NAME);
    String requestObjectSize = requestContext.get(Context.X_OG_OBJECT_SIZE);
    String requestPartSize = requestContext.get(Context.X_OG_MULTIPART_PART_SIZE);
    String requestMaxParts = requestContext.get(Context.X_OG_MULTIPART_MAX_PARTS);
    String requestUploadId = requestContext.get(Context.X_OG_MULTIPART_UPLOAD_ID);
    String requestPartNumber = requestContext.get(Context.X_OG_MULTIPART_PART_NUMBER);
    String responseUploadId = responseContext.get(Context.X_OG_MULTIPART_UPLOAD_ID);
    String responsePartId = responseHeaders.get("ETag");

    MultipartInfo multipartInfo;
    if (multipartrequestOperation.equals(MultipartRequest.INITIATE.toString())) {
      if(response.getStatusCode() != 200) {
        // bad response, so just return
        _logger.info("Multipart Initiate Failed with [{}]", response.getStatusCode());
        this.sessionManager.inProgressSessions.decrementAndGet();
        return;
      }
      multipartInfo = new MultipartInfo(requestContainerName, requestObjectName, responseUploadId,
          Long.parseLong(requestObjectSize), Long.parseLong(requestPartSize), Integer.parseInt(requestMaxParts),
              requestContainerSuffix, requestBodyDataType, requestContext);
      this.multipartRequestMap.put(responseUploadId, multipartInfo);
        // add MultipartInfo only if not added already to actionable multipart sessions list.
        if (!multipartInfo.getInActionableSessions()) {
          this.actionableMultipartSessions.add(multipartInfo);
          multipartInfo.setInActionableSessions(true);
          _logger.debug("Added active Multipart session. count is [{}]", this.actionableMultipartSessions.size());
        }
    } else if (multipartrequestOperation.equals(MultipartRequest.PART.toString())) {
        multipartInfo = multipartRequestMap.get(requestUploadId);
          multipartInfo.finishPartRequest(new PartInfo(requestPartNumber, responsePartId));
          // multipart info only gets put on blocked when INTERNAL_PENDING is
          // observed on the get() call. Put it back in active when all parts are in
          // or if active part uploads is now less than maxParts
          MultipartRequest multipartRequest = multipartInfo.getNextMultipartRequest();
          if (multipartRequest == MultipartRequest.COMPLETE || multipartRequest == MultipartRequest.PART) {
            if (!multipartInfo.getInActionableSessions()) {
              this.actionableMultipartSessions.add(multipartInfo);
              multipartInfo.setInActionableSessions(true);
              _logger.debug("Added active Multipart session. count is [{}]", this.actionableMultipartSessions.size());
            }
          }
    } else if (multipartrequestOperation.equals(MultipartRequest.COMPLETE.toString())) {
      this.sessionManager.inProgressSessions.getAndDecrement();
      multipartInfo = multipartRequestMap.get(requestUploadId);
      multipartInfo.finishCompleteRequest();
      this.multipartRequestMap.remove(multipartInfo);
    } else if (multipartrequestOperation.equals(MultipartRequest.ABORT.toString())) {
      //TODO
      _logger.warn("multipart request operation ABORT - to be implemented");
    }

    sessionManager.sessionsLock.lock();
    try {
      sessionManager.sessionAvailable.signal();
    }
    finally {
      sessionManager.sessionsLock.unlock();
    }

  }

  @Override
  public Request get() {
    final Map<String, String> requestContext = Maps.newHashMap();

    HttpRequest.Builder builder;
    builder = sessionManager.getNextRequest(requestContext);
    if (builder != null) {
      if (this.headers != null) {
        for (final Map.Entry<String, Function<Map<String, String>, String>> header : this.headers
                .entrySet()) {
          builder.withHeader(header.getKey(), header.getValue().apply(requestContext));
        }
      }

      if (this.id != null) {
        builder.withContext(Context.X_OG_REQUEST_ID, this.id.apply(requestContext));
      }

      if (credentials != null) {
        Credential credential = this.credentials.apply(requestContext);
        String username = credential.getUsername();
        String password = credential.getPassword();
        String keystoneToken = credential.getKeystoneToken();

        if (username != null)
          builder.withContext(Context.X_OG_USERNAME, username);
        if (password != null)
          builder.withContext(Context.X_OG_PASSWORD, password);
        if (keystoneToken != null)
          builder.withContext(Context.X_OG_KEYSTONE_TOKEN, keystoneToken);
      }

      for (final Map.Entry<String, String> entry : requestContext.entrySet()) {
        builder.withContext(entry.getKey(), entry.getValue());
      }

      //TODO clean up the magic value
      builder.withContext(Context.X_OG_RESPONSE_BODY_CONSUMER, "s3.multipart");

      return builder.build();
    } else {
      // if the load-scheduler thread is interrupted, return null request. This will
      // happen when the test is stopped. When the test is stopped this request will not be
      // sent any way. so this null request will not be used.
      return null;
    }
  }

  private MultipartInfo getActiveMultipartOperation() {
      int numActionableSessions = this.actionableMultipartSessions.size();
      if (numActionableSessions > 0) {
        int sessionIndex = this.randomNumber.nextInt(numActionableSessions);
        return this.actionableMultipartSessions.get(sessionIndex);
      } else {
        return null;
      }
  }

  private HttpRequest.Builder createInitiateRequest(final Map<String, String> context) {
    Body fullBody = this.body.apply(context);
    Long partSize = this.partSize.apply(context); // bytes
    Integer maxParts = this.partsPerSession.apply(context);

    String containerName = this.container.apply(context);

    final HttpRequest.Builder builder =
        new HttpRequest.Builder(Method.POST,
            getUrl(context, MultipartRequest.INITIATE, NO_PART, null, null, containerName),
            Operation.MULTIPART_WRITE_INITIATE);

    builder.withContext(Context.X_OG_OBJECT_SIZE, String.valueOf(fullBody.getSize()));
    builder.withContext(Context.X_OG_MULTIPART_BODY_DATA_TYPE, fullBody.getDataType().toString());
    builder.withContext(Context.X_OG_MULTIPART_REQUEST, MultipartRequest.INITIATE.toString());
    builder.withContext(Context.X_OG_MULTIPART_CONTAINER, containerName);
    builder.withContext(Context.X_OG_MULTIPART_PART_SIZE, partSize.toString());
    builder.withContext(Context.X_OG_MULTIPART_MAX_PARTS, maxParts.toString());
    return builder;
  }

  private HttpRequest.Builder createPartRequest(final Map<String, String> context,
      int partNumber, String uploadId, long partSize, String bodyDataType, final Map<String, String> multipartContext) {
    final HttpRequest.Builder builder =
        new HttpRequest.Builder(Method.PUT, getUrl(context, MultipartRequest.PART,
            partNumber, uploadId, multipartContext.get(Context.X_OG_OBJECT_NAME),
                multipartContext.get(Context.X_OG_MULTIPART_CONTAINER)), Operation.MULTIPART_WRITE_PART);

    Body body;
    if(bodyDataType.equals(DataType.RANDOM.toString())) {
      body = Bodies.random(partSize);
      builder.withBody(body);
    } else if(bodyDataType.equals(DataType.ZEROES.toString())) {
      body = Bodies.zeroes(partSize);
      builder.withBody(body);
    } else {
      body = Bodies.random(partSize);
      builder.withBody(body);
    }

    if (this.contentMd5) {
      try {
        Long size = body.getSize();
        byte[] md5 = md5ContentCache.get(size);
        builder.withHeader(Context.X_OG_CONTENT_MD5, BaseEncoding.base64().encode(md5));
      } catch (Exception e) {
        _logger.error(e.getMessage());
      }
    }
    // populate request context
    for (final Map.Entry<String, String> entry : multipartContext.entrySet()) {
      context.put(entry.getKey(), entry.getValue());
    }
    context.put(Context.X_OG_MULTIPART_REQUEST, MultipartRequest.PART.toString());
    context.put(Context.X_OG_MULTIPART_PART_NUMBER, String.valueOf(partNumber));
    context.put(Context.X_OG_MULTIPART_UPLOAD_ID, uploadId);
    context.put(Context.X_OG_MULTIPART_PART_SIZE, String.valueOf(partSize));

    return builder;
  }

  private HttpRequest.Builder createCompleteRequest(final Map<String, String> context,
      String uploadId, String body, final Map<String, String> multipartContext) {
    final HttpRequest.Builder builder =
        new HttpRequest.Builder(Method.POST,
            getUrl(context, MultipartRequest.COMPLETE, NO_PART, uploadId, multipartContext.get(Context.X_OG_OBJECT_NAME),
                    multipartContext.get(Context.X_OG_MULTIPART_CONTAINER)), Operation.MULTIPART_WRITE_COMPLETE);

    builder.withBody(Bodies.custom(body.length(), body));

    // populate request context
    for (final Map.Entry<String, String> entry : multipartContext.entrySet()) {
      context.put(entry.getKey(), entry.getValue());
    }
    context.put(Context.X_OG_MULTIPART_REQUEST, MultipartRequest.COMPLETE.toString());
    context.put(Context.X_OG_MULTIPART_UPLOAD_ID, uploadId);

    return builder;
  }

  private HttpRequest.Builder createAbortRequest(final Map<String, String> context, String uploadId,
                                                 final Map<String, String> multipartContext) {
    final HttpRequest.Builder builder =
        new HttpRequest.Builder(Method.DELETE,
            getUrl(context, MultipartRequest.ABORT, NO_PART, uploadId, multipartContext.get(Context.X_OG_OBJECT_NAME),
                    multipartContext.get(Context.X_OG_MULTIPART_CONTAINER)), Operation.MULTIPART_WRITE_ABORT);

    context.put(Context.X_OG_OBJECT_NAME, multipartContext.get(Context.X_OG_OBJECT_NAME));

    return builder;
  }

  private URI getUrl(final Map<String, String> context, MultipartRequest multipartRequest,
      int partNumber, String uploadId, String objectName, String containerName) {

    final StringBuilder s = new StringBuilder().append(this.scheme).append("://");
    appendHost(s, context, containerName);
    appendPort(s);
    appendPath(s, context, multipartRequest, objectName, containerName);
    appendTrailingSlash(s);
    appendQueryParams(s, multipartRequest, partNumber, uploadId);

    try {
      return new URI(s.toString());
    } catch (final URISyntaxException e) {
      // Wrapping checked exception as unchecked because most callers will not be able to handle
      // it and I don't want to include URISyntaxException in the entire signature chain
      throw new IllegalArgumentException(e);
    }
  }

  private void appendHost(final StringBuilder s, final Map<String, String> context,
                          String containerName) {
    if (this.virtualHost) {
      if (containerName != null)  {
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

  private void appendPath(final StringBuilder s, final Map<String, String> context,
      MultipartRequest multipartRequest, String objectName, String containerName) {
    if (!this.virtualHost) {
      s.append("/");
      if (this.uriRoot != null) {
        s.append(this.uriRoot).append("/");
      }

      if (containerName != null) {
        s.append(containerName);
      }
    }

    if (this.object != null && multipartRequest == MultipartRequest.INITIATE) {
      s.append("/").append(this.object.apply(context));
    } else if(objectName != null) {
      s.append("/").append(objectName);
    }
  }

  private void appendTrailingSlash(final StringBuilder s) {
    if (this.trailingSlash) {
      s.append("/");
    }
  }

  private void appendQueryParams(final StringBuilder s, MultipartRequest multipartRequest,
      int partNumber, String uploadId) {
    final Map<String, String> queryParamsMap = Maps.newHashMap();
    String queryParams = null;

    switch(multipartRequest) {
      case INITIATE:
        queryParams = UPLOADS;
        break;
      case PART:
        queryParamsMap.put(PART_NUMBER, String.valueOf(partNumber));
        queryParamsMap.put(UPLOAD_ID, uploadId);
        break;
      case COMPLETE:
        queryParamsMap.put(UPLOAD_ID, uploadId);
        break;
      case ABORT:
        queryParamsMap.put(UPLOAD_ID, uploadId);
        break;
      default:
        return;
    }

    if (!queryParamsMap.isEmpty()) {
      queryParams = PARAM_JOINER.join(queryParamsMap);
    }
    if (queryParams != null && queryParams.length() > 0) {
      s.append("?").append(queryParams);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "RequestSupplier [" + "scheme=%s,%n" + "host=%s,%n" + "port=%s,%n"
            + "uriRoot=%s,%n" + "container=%s,%n" + "object=%s,%n" + "queryParameters=%s,%n"
            + "trailingSlash=%s,%n" + "headers=%s,%n" + "body=%s%n" + "]",
        this.scheme, this.host, this.port, this.uriRoot, this.container, this.object,
        this.queryParameters, this.trailingSlash, this.headers, this.body);
  }
}
