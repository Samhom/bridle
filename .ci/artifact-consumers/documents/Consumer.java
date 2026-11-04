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
    version=args[0];core();origin(io.github.samhom.driverkit.couchbase.CouchbaseAdapter.class,"driverkit-couchbase");
    origin(com.couchbase.client.core.Core.class,"driverkit-couchbase-core");
    Class<?> list=Class.forName("com.couchbase.client.core.deps.com.google.common.collect.ImmutableList");
    origin(list,"driverkit-couchbase-core");
    Object value=list.getMethod("of",Object.class).invoke(null,"fixture");
    check(((List<?>)value).get(0).equals("fixture"),"Relocated Guava cannot execute");
    Class<?> access=Class.forName("com.couchbase.client.core.deps.com.google.common.util.concurrent.internal.InternalFutureFailureAccess");
    origin(access,"driverkit-couchbase-core");
    check(com.couchbase.client.java.json.JsonObject.create().put("value",7).getInt("value")==7,"Native JSON linkage failed");
    Properties metadata=new Properties();
    try(var input=list.getResourceAsStream("/META-INF/maven/com.google.guava/guava/pom.properties")) {
      check(input!=null,"Guava package metadata missing");metadata.load(input);
    }
    check("33.7.2-jre".equals(metadata.getProperty("version")),"Embedded Guava is not patched");
  }
}
