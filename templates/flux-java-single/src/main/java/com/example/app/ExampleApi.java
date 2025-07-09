package com.example.app;

import io.fluxcapacitor.javaclient.web.HandleGet;
import io.fluxcapacitor.javaclient.web.Path;
import io.fluxcapacitor.javaclient.web.PathParam;
import io.fluxcapacitor.javaclient.web.ServeStatic;
import org.springframework.stereotype.Component;

@Component
@Path("/example")
@ServeStatic(value = "/static")
public class ExampleApi {

    @HandleGet("/{number}")
    Integer getNumber(@PathParam Integer number) {
        return number;
    }

}
