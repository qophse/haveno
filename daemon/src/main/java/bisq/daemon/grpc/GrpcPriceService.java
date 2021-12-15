/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.daemon.grpc;

import bisq.core.api.CoreApi;
import bisq.core.api.model.MarketPriceInfo;

import bisq.proto.grpc.MarketPriceReply;
import bisq.proto.grpc.MarketPriceRequest;
import bisq.proto.grpc.MarketPricesReply;
import bisq.proto.grpc.MarketPricesRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.PriceGrpc.PriceImplBase;
import static bisq.proto.grpc.PriceGrpc.getGetMarketPriceMethod;
import static java.util.concurrent.TimeUnit.SECONDS;



import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;

@Slf4j
class GrpcPriceService extends PriceImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcPriceService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getMarketPrice(MarketPriceRequest req,
                               StreamObserver<MarketPriceReply> responseObserver) {
        try {
            double marketPrice = coreApi.getMarketPrice(req.getCurrencyCode());
            responseObserver.onNext(MarketPriceReply.newBuilder().setPrice(marketPrice).build());
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getMarketPrices(MarketPricesRequest request,
                                StreamObserver<MarketPricesReply> responseObserver) {
        try {
            responseObserver.onNext(mapMarketPricesReply(coreApi.getMarketPrices()));
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    private MarketPricesReply mapMarketPricesReply(List<MarketPriceInfo> marketPrices) {
        MarketPricesReply.Builder builder = MarketPricesReply.newBuilder();
        marketPrices.stream()
                .map(MarketPriceInfo::toProtoMessage)
                .forEach(builder::addMarketPrice);
        return builder.build();
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    final Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            put(getGetMarketPriceMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
