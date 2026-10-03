package sc.fiji.oc3dsd.cli;
import sc.fiji.oc3dsd.runtime.StarDist3D;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class StarDist3DCLI {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: StarDist3DCLI request.json");
        JsonObject request;try(Reader reader=new InputStreamReader(new FileInputStream(args[0]),StandardCharsets.UTF_8)){request=JsonParser.parseReader(reader).getAsJsonObject();}
        System.out.println(StarDist3D.JSON.toJson(StarDist3D.call(request.get("action").getAsString(),request.getAsJsonObject("parameters"),System.err::println)));
    }
}
