import io.github.samhom.driverkit.core.*;
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
    origin(ClientRuntime.class,"driverkit-core");
    try(ClientRuntime runtime=new ClientRuntime()) {
      var handle=runtime.register(new ConfigSnapshot<>(new ResourceId("consumer","test","resource"),1,"fixture"),
        new FactoryAdapter<String,String>("fixture",v->v,(v,t)->{},v->{}),ClientPolicy.defaults());
      check("fixture".equals(handle.execute(Operation.read("get",Duration.ofSeconds(1)),(v,ctx)->v)),"Core invocation failed");
    }
  }
  public static void main(String[] args) throws Exception {
    version=args[0];core();origin(io.github.samhom.driverkit.redis.JedisAdapter.class,"driverkit-redis-jedis");
    var settings=io.github.samhom.driverkit.redis.RedisSettings.defaults(new Endpoint("localhost",6379,false),()->new Credentials("fixture",new char[0]));
    try(var nativeClient=new io.github.samhom.driverkit.redis.JedisAdapter().create(settings)) {
      check(nativeClient.getClass()==redis.clients.jedis.RedisClient.class,"Wrong modern Jedis client");
      check(nativeClient.getPool()!=null,"Connection pool missing");
    }
    check(redis.clients.jedis.RedisClient.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("jedis-8.0.2.jar"),"Native generation drift");
  }
}
