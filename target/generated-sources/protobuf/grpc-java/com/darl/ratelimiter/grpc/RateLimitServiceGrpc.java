package com.darl.ratelimiter.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: ratelimit.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RateLimitServiceGrpc {

  private RateLimitServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "darl.ratelimit.RateLimitService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.darl.ratelimiter.grpc.CheckLimitRequest,
      com.darl.ratelimiter.grpc.CheckLimitResponse> getCheckLimitMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CheckLimit",
      requestType = com.darl.ratelimiter.grpc.CheckLimitRequest.class,
      responseType = com.darl.ratelimiter.grpc.CheckLimitResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.darl.ratelimiter.grpc.CheckLimitRequest,
      com.darl.ratelimiter.grpc.CheckLimitResponse> getCheckLimitMethod() {
    io.grpc.MethodDescriptor<com.darl.ratelimiter.grpc.CheckLimitRequest, com.darl.ratelimiter.grpc.CheckLimitResponse> getCheckLimitMethod;
    if ((getCheckLimitMethod = RateLimitServiceGrpc.getCheckLimitMethod) == null) {
      synchronized (RateLimitServiceGrpc.class) {
        if ((getCheckLimitMethod = RateLimitServiceGrpc.getCheckLimitMethod) == null) {
          RateLimitServiceGrpc.getCheckLimitMethod = getCheckLimitMethod =
              io.grpc.MethodDescriptor.<com.darl.ratelimiter.grpc.CheckLimitRequest, com.darl.ratelimiter.grpc.CheckLimitResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CheckLimit"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.darl.ratelimiter.grpc.CheckLimitRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.darl.ratelimiter.grpc.CheckLimitResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RateLimitServiceMethodDescriptorSupplier("CheckLimit"))
              .build();
        }
      }
    }
    return getCheckLimitMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RateLimitServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceStub>() {
        @java.lang.Override
        public RateLimitServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitServiceStub(channel, callOptions);
        }
      };
    return RateLimitServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RateLimitServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceBlockingStub>() {
        @java.lang.Override
        public RateLimitServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitServiceBlockingStub(channel, callOptions);
        }
      };
    return RateLimitServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RateLimitServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitServiceFutureStub>() {
        @java.lang.Override
        public RateLimitServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitServiceFutureStub(channel, callOptions);
        }
      };
    return RateLimitServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Attempt to consume one token for the given client.
     * Returns the same decision data as the REST endpoint.
     * </pre>
     */
    default void checkLimit(com.darl.ratelimiter.grpc.CheckLimitRequest request,
        io.grpc.stub.StreamObserver<com.darl.ratelimiter.grpc.CheckLimitResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCheckLimitMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RateLimitService.
   */
  public static abstract class RateLimitServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RateLimitServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RateLimitService.
   */
  public static final class RateLimitServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RateLimitServiceStub> {
    private RateLimitServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Attempt to consume one token for the given client.
     * Returns the same decision data as the REST endpoint.
     * </pre>
     */
    public void checkLimit(com.darl.ratelimiter.grpc.CheckLimitRequest request,
        io.grpc.stub.StreamObserver<com.darl.ratelimiter.grpc.CheckLimitResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCheckLimitMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RateLimitService.
   */
  public static final class RateLimitServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RateLimitServiceBlockingStub> {
    private RateLimitServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Attempt to consume one token for the given client.
     * Returns the same decision data as the REST endpoint.
     * </pre>
     */
    public com.darl.ratelimiter.grpc.CheckLimitResponse checkLimit(com.darl.ratelimiter.grpc.CheckLimitRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCheckLimitMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RateLimitService.
   */
  public static final class RateLimitServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RateLimitServiceFutureStub> {
    private RateLimitServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Attempt to consume one token for the given client.
     * Returns the same decision data as the REST endpoint.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.darl.ratelimiter.grpc.CheckLimitResponse> checkLimit(
        com.darl.ratelimiter.grpc.CheckLimitRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCheckLimitMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHECK_LIMIT = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CHECK_LIMIT:
          serviceImpl.checkLimit((com.darl.ratelimiter.grpc.CheckLimitRequest) request,
              (io.grpc.stub.StreamObserver<com.darl.ratelimiter.grpc.CheckLimitResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getCheckLimitMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.darl.ratelimiter.grpc.CheckLimitRequest,
              com.darl.ratelimiter.grpc.CheckLimitResponse>(
                service, METHODID_CHECK_LIMIT)))
        .build();
  }

  private static abstract class RateLimitServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RateLimitServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.darl.ratelimiter.grpc.RateLimitProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RateLimitService");
    }
  }

  private static final class RateLimitServiceFileDescriptorSupplier
      extends RateLimitServiceBaseDescriptorSupplier {
    RateLimitServiceFileDescriptorSupplier() {}
  }

  private static final class RateLimitServiceMethodDescriptorSupplier
      extends RateLimitServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RateLimitServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RateLimitServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RateLimitServiceFileDescriptorSupplier())
              .addMethod(getCheckLimitMethod())
              .build();
        }
      }
    }
    return result;
  }
}
