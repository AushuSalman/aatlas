package com.aatlas.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aatlas.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    @DisplayName("accepts an ordinary password inside the window")
    void acceptsAnOrdinaryPassword() {
        // No composition rules by design: this passes on length and on not being obvious,
        // not because it happens to carry a symbol and a digit.
        assertThatCode(() -> policy.check("Zephyr!42Bridge", "alex@kestrel.com"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "abc", "seven77", "       "})
    @DisplayName("rejects anything under eight characters")
    void rejectsShortPasswords(String password) {
        assertThatThrownBy(() -> policy.check(password, "alex@kestrel.com"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("rejects anything over twenty characters")
    void rejectsLongPasswords() {
        // Twenty-one plain characters: inside BCrypt's byte limit, outside the product's
        // character limit, so this proves the length rule rather than the byte rule.
        assertThatThrownBy(() -> policy.check("abcdefghijklmnopqrstu", "alex@kestrel.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("between 8 and 20");
    }

    @Test
    @DisplayName("eight and twenty characters are both accepted")
    void boundariesAreInclusive() {
        assertThatCode(() -> policy.check("Zx9!mQ2v", "alex@kestrel.com")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("Zx9!mQ2vLp4Kt7Rb2Nc8", "alex@kestrel.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the character limit currently subsumes BCrypt's byte limit")
    void characterLimitKeepsUsInsideBcryptsByteLimit() {
        // BCrypt hashes the first 72 bytes and drops the rest in silence, so a password
        // past that limit and a truncation of it would open the same account.
        //
        // MAX_LENGTH counts UTF-16 code units and the worst case is three bytes each: a
        // four-byte character costs two units, so it averages two. Twenty units therefore
        // top out at sixty bytes and the byte guard cannot fire today.
        assertThat(PasswordPolicy.MAX_LENGTH * 3)
                .as("raise MAX_LENGTH past 24 and the byte guard becomes load-bearing again")
                .isLessThanOrEqualTo(PasswordPolicy.MAX_BYTES);

        // The densest input the length rule allows: twenty three-byte characters. They have
        // to differ from each other, or the screen for a repeated character fires first.
        String densest = "日月火水木金土山川空海風雨雪花草石城市林";
        assertThat(densest).hasSize(PasswordPolicy.MAX_LENGTH);
        assertThat(densest.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(PasswordPolicy.MAX_BYTES);
        assertThatCode(() -> policy.check(densest, "alex@kestrel.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects an obvious choice regardless of case")
    void rejectsObviousPasswords() {
        assertThatThrownBy(() -> policy.check("PasswordPassword", "alex@kestrel.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("easy to guess");
    }

    @Test
    @DisplayName("rejects one character repeated")
    void rejectsRepeatedCharacter() {
        assertThatThrownBy(() -> policy.check("aaaaaaaaaaaaaaaa", "alex@kestrel.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("one character repeated");
    }

    @Test
    @DisplayName("rejects a password containing the email local part")
    void rejectsPasswordContainingEmail() {
        assertThatThrownBy(() -> policy.check("moreno!42Br", "moreno@kestrel.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("email address");
    }

    @Test
    @DisplayName("a very short local part does not ban half the dictionary")
    void shortLocalPartIsNotScreened() {
        // "jo" appears inside plenty of good passwords; banning it would cost more than it
        // saves. Twenty characters exactly, which also pins the upper boundary.
        assertThatCode(() -> policy.check("jovial mountain trek", "jo@kestrel.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failures are 400 weak_password, so the form can show them verbatim")
    void failuresCarryAUsableCode() {
        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> policy.check("short", "alex@kestrel.com"));

        assertThat(thrown.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.code()).isEqualTo("weak_password");
    }
}
