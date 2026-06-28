package com.darl.ratelimiter.grpc;

import com.darl.ratelimiter.ratelimit.RateLimitResult;
import com.darl.ratelimiter.ratelimit.RateLimitService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Day 10: gRPC endpoint for rate limit checks.
 *
 * Runs on port 9090 (configured via grpc.server.port in application.yml).
 * REST API on port 8080 continues to work alongside — both endpoints share
 * the same {@link RateLimitService} and therefore the same Redis counters.
 *
 * Generated stubs ({@code RateLimitServiceGrpc}) are produced by the
 * protobuf-maven-plugin during {@code mvn generate-sources} from
 * {@code src/main/proto/ratelimit.proto}.
 *
 * Quick test with grpcurl:
 * <pre>
 *   grpcurl -plaintext -d '{"client_id":"client-1"}' \
 *       localhost:9090 darl.ratelimit.RateLimitService/CheckLimit
 * </pre>
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class GrpcRateLimitService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final RateLimitService rateLimitService;

    @Override
    public void checkLimit(CheckLimitRequest request,
                           StreamObserver<CheckLimitResponse> responseObserver) {
        String clientId = request.getClientId();

        if (clientId == null || clientId.isBlank()) {
            responseObserver.onError(
                    io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("client_id must not be blank")
                            .asRuntimeException()
            );
            return;
        }

        try {
            RateLimitResult result = rateLimitService.checkLimit(clientId);

            CheckLimitResponse response = CheckLimitResponse.newBuilder()
                    .setAllowed(result.isAllowed())
                    .setClientId(clientId)
                    .setLimit(result.getLimit())
                    .setRemaining(result.getRemaining())
                    .setResetAtEpochSecond(result.getResetAtEpochSecond())
                    .setAlgorithm(result.getAlgorithm())
                    .setMessage(result.isAllowed() ? "OK" : "Rate limit exceeded")
                    .build();

            log.debug("[gRPC] checkLimit clientId={} allowed={} remaining={}",
                    clientId, result.isAllowed(), result.getRemaining());

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("[gRPC] unexpected error for clientId={}: {}", clientId, e.getMessage(), e);
            responseObserver.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription("Internal server error")
                            .withCause(e)
                            .asRuntimeException()
            );
        }
    }
}
