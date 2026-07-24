@ApiDoc
@ApiDocInfo(
        title = "User Management API",
        version = "1.0",
        description = """
                API for managing user profiles.

                Create user profiles and retrieve the profiles available to the current user.
                """,
        serveOpenApi = true,
        serveApiReference = true)
@RequiresUser
@Path("/api")
package com.example.app;

import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.Path;
