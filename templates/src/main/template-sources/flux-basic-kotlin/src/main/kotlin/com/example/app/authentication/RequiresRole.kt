package com.example.app.authentication

import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole
import java.lang.annotation.Inherited

@Target(AnnotationTarget.TYPE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@RequiresAnyRole
annotation class RequiresRole(vararg val value: Role)
