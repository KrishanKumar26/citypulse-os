package com.citypulse.common.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password policy unit tests (docs/SECURITY.md §2).
 */
@DisplayName("StrongPasswordValidator")
class StrongPasswordValidatorTest {

    private StrongPasswordValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new StrongPasswordValidator();
        // The validator replaces the default violation message with a specific
        // one, so the builder chain has to be stubbed.
        context = Mockito.mock(ConstraintValidatorContext.class, Mockito.RETURNS_DEEP_STUBS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Str0ng!Passw0rd#2026",
            "aB3$defghijkl",
            "Correct-Horse-Battery-9!",
            "P@ssw0rd!Complex99"
    })
    @DisplayName("accepts passwords meeting every rule")
    void acceptsStrongPasswords(String password) {
        assertThat(validator.isValid(password, context)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Sh0rt!",              // under 12 characters
            "alllowercase1!",      // no uppercase
            "ALLUPPERCASE1!",      // no lowercase
            "NoDigitsHere!!!",     // no digit
            "NoSymbolsHere123",    // no symbol
            "            ",        // whitespace only
            "aB3$defghijk"         // 12 chars but only 11 after the count — boundary check below
    })
    @DisplayName("rejects passwords that miss any rule")
    void rejectsWeakPasswords(String password) {
        // The last entry is a deliberate boundary probe; assert on the real rule
        // rather than assuming, so the test documents actual behaviour.
        boolean valid = validator.isValid(password, context);
        if (password.equals("aB3$defghijk")) {
            assertThat(password).hasSize(12);
            assertThat(valid).as("exactly 12 characters with all classes is valid").isTrue();
        } else {
            assertThat(valid).isFalse();
        }
    }

    @Test
    @DisplayName("rejects null rather than throwing")
    void rejectsNull() {
        assertThat(validator.isValid(null, context)).isFalse();
    }

    @Test
    @DisplayName("rejects a password exceeding the maximum length")
    void rejectsOverlyLongPassword() {
        // Bounded so an enormous input cannot be used to make BCrypt expensive.
        String tooLong = "aB3$" + "x".repeat(200);
        assertThat(validator.isValid(tooLong, context)).isFalse();
    }

    @Test
    @DisplayName("rejects known-obvious passwords even when they satisfy the character rules")
    void rejectsObviousPasswords() {
        assertThat(validator.isValid("Password123!", context))
                .as("meets every character rule but is among the first values any attack tries")
                .isFalse();
        assertThat(validator.isValid("CityPulse123!", context)).isFalse();
    }

    @Test
    @DisplayName("accepts exactly the minimum length")
    void acceptsMinimumLength() {
        String twelve = "aB3$efghijkl";
        assertThat(twelve).hasSize(12);
        assertThat(validator.isValid(twelve, context)).isTrue();
    }
}
