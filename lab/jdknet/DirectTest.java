import java.net.*; import java.net.http.*; import java.time.Duration;
public class DirectTest {
  public static void main(String[] a) throws Exception {
    for (String u : a) {
      try {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("OK   " + r.statusCode() + "  " + u);
      } catch (Exception e) {
        Throwable t = e; while (t.getCause() != null) t = t.getCause();
        System.out.println("FAIL " + u + "  <- " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace("\n"," "));
      }
    }
  }
}
