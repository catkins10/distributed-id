package com.yd.distributedid.sdks;

import com.yd.distributedid.core.SnowFlake;
import com.yd.distributedid.exception.RemotingTooMuchRequestException;
import com.yd.distributedid.util.GlobalConfig;
import com.yd.distributedid.util.NettyUtil;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 通过雪花算法生成唯一ID，写入Channel返回
 *
 * @author Yd
 */
public class SdkServerHandler extends SimpleChannelInboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(SdkServerHandler.class);
    /**
     * 通过信号量来控制流量
     */
    private Semaphore semaphore = new Semaphore(GlobalConfig.HANDLE_SDKS_TPS);
    private SnowFlake snowFlake;

    public SdkServerHandler(SnowFlake snowFlake) {
        this.snowFlake = snowFlake;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg != null && msg instanceof SdkProto) {
            SdkProto sdkProto = (SdkProto) msg;
            logger.info("SdkServerHandler msg is: {}", sdkProto.toString());
            if (semaphore.tryAcquire(GlobalConfig.ACQUIRE_TIMEOUTMILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    //获取请求中的请求id，数据中心id进行动态性sdk 获取分布式id
                    snowFlake.setDatacenterId(sdkProto.getDid());
                    snowFlake.setMachineId(sdkProto.getRqid());
                    sdkProto.setId(snowFlake.nextId());
                    ctx.channel().writeAndFlush(sdkProto).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) throws Exception {
                            semaphore.release();
                        }
                    });
                } catch (Exception e) {
                    semaphore.release();
                    logger.error("SdkServerhandler error", e);
                }
            } else {
                sdkProto.setId(-1);
                ctx.channel().writeAndFlush(sdkProto);
                String info = String.format("SdkServerHandler tryAcquire semaphore timeout, %dms, waiting thread " +
                                "nums: %d availablePermit: %d",     //
                        GlobalConfig.ACQUIRE_TIMEOUTMILLIS, //
                        this.semaphore.getQueueLength(),    //
                        this.semaphore.availablePermits()   //
                );
                logger.warn(info);
                throw new RemotingTooMuchRequestException(info);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Channel channel = ctx.channel();
        logger.error("SdkServerHandler channel [{}] error and will be closed", NettyUtil.parseRemoteAddr(channel), cause.getMessage());
        NettyUtil.closeChannel(channel);
    }
}
