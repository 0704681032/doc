package com.jyy;

import org.springframework.cglib.proxy.*;

/**
 * Created by jyy on 2019-03-25
 */
//java永久代去哪了
//https://infoq.cn/article/Java-PERMGEN-Removed

//-XX:MetaspaceSize=50m -XX:MaxMetaspaceSize=256m

//https://www.jianshu.com/p/b448c21d2e71
//任何一个JVM参数的默认值可以通过java -XX:+PrintFlagsFinal -version |grep JVMParamName获取，
// 例如：java -XX:+PrintFlagsFinal -version |grep MetaspaceSize

//如果没有配置-XX:MetaspaceSize，那么触发FGC的阈值是21807104（约20.8m），
// 可以通过jinfo -flag MetaspaceSize pid得到这个值；
public class MetaspaceOutofmemoryErrorTest {

    static class Holder {

    }

    public static void main(String[] args) {
        for (;;) {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Holder.class);
            enhancer.setCallback((MethodInterceptor) (o, method, objects, methodProxy) -> methodProxy.invokeSuper(o,args));
            enhancer.setCallbackFilter(method -> 0);
            enhancer.setUseCache(false);
            enhancer.create();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }
}
