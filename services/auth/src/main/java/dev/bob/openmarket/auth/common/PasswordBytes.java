package dev.bob.openmarket.auth.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Caps a password by its UTF-8 byte length, not character count. Bcrypt
 * only hashes the first 72 bytes — anything beyond is silently ignored at
 * verification time, so a longer @Size cap would accept passwords that
 * behave differently than the user believes. Keeping the limit at the
 * algorithm boundary makes what you set exactly what gets checked.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordBytes.Validator.class)
public @interface PasswordBytes {

    int max() default 72;

    String message() default "password must be at most {max} bytes (UTF-8)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PasswordBytes, String> {

        private int max;

        @Override
        public void initialize(PasswordBytes annotation) {
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true; // @NotBlank handles null/blank
            }
            return value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
