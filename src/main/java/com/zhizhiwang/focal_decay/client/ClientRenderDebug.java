package com.zhizhiwang.focal_decay.client;

/**
 * 渲染侧诊断开关（{@code /focaldecay trace true} 切换）。
 * <p>
 * 区块编译跑在<b>工作线程</b>上，加日志会刷屏，所以这里只保留一个可以安全跨线程读取的开关，
 * 具体输出交给服务端的右键判定链（见 {@code InteractionHandler}）。
 * <p>
 * 保留这个类而不是删掉，是为了让"渲染侧开关"有一个明确的落点：需要时可以在
 * {@code ClientRenderCache} 的表面扫描里按它输出 ASCII 日志（注意别写中文，控制台是 GBK）。
 */
public final class ClientRenderDebug {

    /** 由指令切换。volatile：区块编译线程要读，指令线程要写。 */
    public static volatile boolean enabled;

    private ClientRenderDebug() {
    }
}
