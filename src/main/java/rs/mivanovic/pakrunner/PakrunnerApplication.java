package rs.mivanovic.pakrunner;

//import org.springdoc.core.GroupedOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PakrunnerApplication {

    /*
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("pakrunner-public")
                .pathsToMatch("/**")
                .build();
    }
*/

    public static void main(String[] args) {
        SpringApplication.run(PakrunnerApplication.class, args);
    }

}
