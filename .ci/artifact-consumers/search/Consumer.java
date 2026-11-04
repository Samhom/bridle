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
    version=args[0];core();origin(io.github.samhom.driverkit.elasticsearch.ElasticsearchAdapter.class,"driverkit-elasticsearch");
    origin(co.elastic.clients.transport.rest5_client.low_level.Rest5Client.class,"driverkit-elasticsearch-transport");
    try(var transport=co.elastic.clients.transport.rest5_client.low_level.Rest5Client.builder(java.net.URI.create("http://localhost:1")).build()) {
      check(transport!=null,"Patched HTTP transport failed to initialize");
    }
  }
}
