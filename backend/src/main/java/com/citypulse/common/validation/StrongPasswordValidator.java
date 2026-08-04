package com.citypulse.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.Set;

/**
 * Password policy: at least 12 characters with upper case, lower case, a digit,
 * and a symbol, and not one of a small set of obviously compromised values.
 *
 * <p>Length is the dominant factor, so the minimum is 12 rather than the more
 * common 8. The blocklist is intentionally tiny — a real breached-password check
 * belongs against a maintained corpus (for example a k-anonymity lookup against
 * Have I Been Pwned), which is recorded as a hardening task rather than faked
 * here with a token list pretending to be comprehensive.
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;

    /** Only the values common enough to be tried first in any attack. */
    private static final Set<String> OBVIOUS_PASSWORDS = Set.of(
            "password123!", "passw0rd123!", "administrator1!", "qwerty123456!",
            "welcome12345", "changeme1234", "letmein12345", "citypulse123!");

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }

        List<String> failures = new java.util.ArrayList<>();

        if (password.length() < MIN_LENGTH) {
            failures.add("at least %d characters".formatted(MIN_LENGTH));
        }
        if (password.length() > MAX_LENGTH) {
            // Bounded so a very long input cannot be used to make BCrypt expensive.
            failures.add("at most %d characters".formatted(MAX_LENGTH));
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            failures.add("an uppercase letter");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            failures.add("a lowercase letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            failures.add("a digit");
        }
        if (password.chars().noneMatch(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch))) {
            failures.add("a symbol");
        }
        if (OBVIOUS_PASSWORDS.contains(password.toLowerCase())) {
            failures.add("a value that is not commonly used");
        }

        if (failures.isEmpty()) {
            return true;
        }

        // Report what is missing rather than a generic rejection: a user who
        // cannot tell why a password failed picks a worse one.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("Password must contain " + String.join(", ", failures))
                .addConstraintViolation();
        return false;
    }
}
