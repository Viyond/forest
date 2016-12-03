package com.dempe.forest.client.proxy;

import com.dempe.forest.Constants;
import com.dempe.forest.ForestUtil;
import com.dempe.forest.client.ChannelPool;
import com.dempe.forest.codec.Header;
import com.dempe.forest.codec.Message;
import com.dempe.forest.codec.Response;
import com.dempe.forest.codec.compress.Compress;
import com.dempe.forest.codec.serialize.Serialization;
import com.dempe.forest.core.CompressType;
import com.dempe.forest.core.ProtoVersion;
import com.dempe.forest.core.SerializeType;
import com.dempe.forest.core.annotation.ServiceExport;
import com.dempe.forest.core.annotation.MethodProvider;
import com.dempe.forest.core.exception.ForestFrameworkException;
import com.dempe.forest.transport.NettyResponseFuture;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created with IntelliJ IDEA.
 * User: Dempe
 * Date: 2016/12/1
 * Time: 9:47
 * To change this template use File | Settings | File Templates.
 */
public class ReferMethodInterceptor implements MethodInterceptor {

    private final static AtomicLong id = new AtomicLong(0);

    private ChannelPool channelPool;
    private Class clz;

    LoadingCache<Method, Header> headerCache = CacheBuilder.newBuilder()
            .maximumSize(100000)
            .build(new CacheLoader<Method, Header>() {
                @Override
                public Header load(Method method) throws Exception {
                    MethodProvider methodProvider = method.getAnnotation(MethodProvider.class);
                    ServiceExport action = (ServiceExport) clz.getAnnotation(ServiceExport.class);
                    if (methodProvider == null || action == null) {
                        new ForestFrameworkException("method annotation Export or Action is null ");
                    }
                    String value = null;//Strings.isNullOrEmpty(action.value()) ? method.getClass().getSimpleName() : action.value();
                    String uri = Strings.isNullOrEmpty(methodProvider.methodName()) ? method.getName() : methodProvider.methodName();
                    long timeOut = methodProvider.timeout() <= 0 ? 5000 : methodProvider.timeout();
                    String headerURI = ForestUtil.buildUri(value, uri);
                    byte extend = ForestUtil.getExtend(methodProvider.serializeType(), methodProvider.compressType());
                    return new Header(Constants.MAGIC, ProtoVersion.VERSION_1.getVersion(), extend, headerURI, timeOut);
                }
            });

    // TODO 容灾&负载均衡的支持
    public ReferMethodInterceptor(ChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    public ReferMethodInterceptor(Class clz, ChannelPool channelPool) {
        this.channelPool = channelPool;
        this.clz = clz;
    }

    public long nextMessageId() {
        return id.incrementAndGet();
    }

    @Override
    public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
        long messageID = nextMessageId();
        Header header = headerCache.get(method).clone();
        header.setMessageID(messageID);
        Message message = new Message();
        message.setHeader(header);
        Compress compress = CompressType.getCompressTypeByValueByExtend(header.getExtend());
        Serialization serialization = SerializeType.getSerializationByExtend(header.getExtend());
        byte[] serialize = serialization.serialize(objects);
        message.setPayload(compress.compress(serialize));
        NettyResponseFuture<Response> responseFuture = channelPool.write(message, header.getTimeOut());
        return responseFuture.getPromise().await().getResult();
    }

}
