package com.citypulse.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces the password policy server-side. The frontend shows the same rules,
 * but this annotation is what actually decides — client-side validation is a
 * convenience, never a control (docs/SECURITY.md §2).
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "Password does not meet the security policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
