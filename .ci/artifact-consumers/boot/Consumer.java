import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
public final class Consumer {
  static String version;
  static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static void origin(Class<?> type,String artifact) throws Exception {
    Path source=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    check(source.getFileName().toString().equals(artifact+"-"+version+".jar"),"Unexpected class origin: "+type.getName()+" from "+source.getFileName());
    System.out.println(type.getName()+" <- "+source.getFileName());
  }
  static void missing(String name) throws Exception {
    try{Class.forName(name);throw new AssertionError("Unexpected optional dependency: "+name);}catch(ClassNotFoundException expected){}
  }
  static void core() throws Exception {
    origin(ClientRuntime.class,"bridle-core");
    try(ClientRuntime runtime=new ClientRuntime()) {
      var handle=runtime.register(new ConfigSnapshot<>(new ResourceId("consumer","test","resource"),1,"fixture"),
        new FactoryAdapter<String,String>("fixture",v->v,(v,t)->{},v->{}),ClientPolicy.defaults());
      check("fixture".equals(handle.execute(Operation.read("get",Duration.ofSeconds(1)),(v,ctx)->v)),"Core invocation failed");
    }
  }
  @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  static class Application {}
  public static void main(String[] args) throws Exception {
    version=args[0];core();origin(io.github.samhom.bridle.spring.BridleAutoConfiguration.class,"bridle-spring-boot");
    for(String absent:List.of("redis.clients.jedis.RedisClient","co.elastic.clients.elasticsearch.ElasticsearchClient",
      "com.couchbase.client.java.Cluster","org.apache.rocketmq.client.producer.DefaultMQProducer",
      "org.springframework.boot.actuate.health.HealthIndicator"))missing(absent);
    ClientRuntime runtime;
    try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(Application.class)) {
      runtime=context.getBean(ClientRuntime.class);
      check(context.getBean(io.github.samhom.bridle.spring.BridleResources.class).clients().isEmpty(),"Unexpected resources");
      check(context.getBeansOfType(io.github.samhom.bridle.spring.ResourceFactory.class).isEmpty(),"Optional native factory linked without native client");
    }
    try {runtime.register(new ConfigSnapshot<>(new ResourceId("consumer","test","closed"),1,"fixture"),
      new FactoryAdapter<String,String>("fixture",v->v,(v,t)->{},v->{}),ClientPolicy.defaults());
      throw new AssertionError("Boot context leaked runtime");}catch(ClientException expected){check(expected.kind()==ClientException.Kind.CLOSED,"Unexpected close outcome");}
  }
}
