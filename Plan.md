# 基于istio的测试环境路由工具、分组路由工具

## 架构
基于graalvm jdk25，使用java operator sdk编写的kubernetes sdk应用。
使用springboot 4.0.1框架作为应用主体, 使用gradle 最新版作为项目构建和管理工具
使用spring boot native构建原生二进制
使用josdk-spring-boot-starter 6.3.1


## 设想
这个sdk会注册一个新的k8s resource。名为serviceEnv。istio的service在这个serviceEnv内进行路由。
这个resource定义了一个基于istio实现的流量隔离的分组。但是这个resource除了支持全局唯一的命名以外，还支持
设置fallback env：这个ServiceEnv中的微服务A如果正在调用另一个微服务B，但是微服务B目前没有在当前
serviceEnv中存在，那么就路由进入到fallbackEnv。如果这个调用还涉及到更多微服务，那么下游的其他所有微服务也是一样的逻辑：优先走入口指定的serviceEnv，如果没有的话就走到兜底的fallbackEnv。兜底的fallbackEnv不会改变整个主要的环境标，在整个链条中都是优先走环境标指定的环境，是需要全链路保持指定环境标的。

当创建好一个serviceEnv之后，目前还没有任何应用或者service加入当前环境。应用或者实例加入当前环境，应该是由deployment或者pod的metadata，如果带上了我的这个operator特定的metadata的标识，这个时候自动加入到我的serviceEnv中。我不确定这个是不是符合k8s operator的最佳实践，你应该给我建议这是不是合理的。也就是说，我目前想要的是一个应用主动声明加入环境，而不是环境声明自己的包含哪些应用的。

## Do not！
1.不要设计成请求失败才走兜底逻辑，不是要基于istio的错误兜底来做路由兜底。而是在istio路由前，就通过路由信息决定路由到什么实例。


## Need！
在依赖istio的能力方面，应该符合istio和k8s的最佳实践！优先使用istio官方推荐使用的特性来解决流量管理问题
整个程序的架构应该足够robust，使用简单的逻辑设计完成最稳定的逻辑。
你应该写一份readMe文档告诉我整个的设计架构，还有注意事项，以及tradeOff点。
你还需要在一个example文件中，告诉我不同的用例场景下，我如何使用这个operator。
我需要使用istio，所以你还要为项目导入合适的istio model api的包，确保这个工程可以正常的使用istio的resource


## 需要你的建议
我现在在考虑使用josdk-spring-boot-starter来构建我的operator。但是我看到kubernetes-client/java是k8s官方项目，也提供了springboot集成和spring aot支持。所以我不确定哪个更好一些

## must
绝对不要修改这个Plan.md文件，你如果需要修改或者创建内容都应该避开这个文件