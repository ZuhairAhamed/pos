package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.SettingType;
import org.junit.jupiter.api.Test;

class SettingTypeTest {

    @Test
    void stringRejectsBlank() {
        assertThatThrownBy(() -> SettingType.STRING.validate("  ")).isInstanceOf(DomainException.class);
        assertThatCode(() -> SettingType.STRING.validate("x")).doesNotThrowAnyException();
    }

    @Test
    void booleanAcceptsTrueFalseCaseInsensitive() {
        assertThatCode(() -> SettingType.BOOLEAN.validate("TRUE")).doesNotThrowAnyException();
        assertThatCode(() -> SettingType.BOOLEAN.validate("false")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.BOOLEAN.validate("yes")).isInstanceOf(DomainException.class);
    }

    @Test
    void intRejectsNonNumberAndNegative() {
        assertThatCode(() -> SettingType.INT.validate("7")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.INT.validate("x")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.INT.validate("-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void decimalRejectsNonNumberAndNegative() {
        assertThatCode(() -> SettingType.DECIMAL.validate("0.15")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.DECIMAL.validate("abc")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.DECIMAL.validate("-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void percentRejectsOutOfRange() {
        assertThatCode(() -> SettingType.PERCENT.validate("10")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.PERCENT.validate("150")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.PERCENT.validate("-5")).isInstanceOf(DomainException.class);
    }

    @Test
    void csvRejectsEmpty() {
        assertThatCode(() -> SettingType.CSV.validate("A,B")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.CSV.validate("  ")).isInstanceOf(DomainException.class);
    }
}
