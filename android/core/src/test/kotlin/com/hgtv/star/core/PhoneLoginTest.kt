// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneLoginTest {
    @Test fun formatByRegion() {
        assertEquals("13800138000", PhoneLogin.format("86", "138 0013 8000"))
        assertEquals("+886 912345678", PhoneLogin.format("886", "0912-345-678"))
        assertEquals("+852 91234567", PhoneLogin.format("+852", "9123 4567"))
        assertThrows(IllegalArgumentException::class.java) { PhoneLogin.format("86", "0912345678") }
        // E.164：区号 + 号码超过 15 位、区号 4 位、号码不足 4 位都不收。
        assertThrows(IllegalArgumentException::class.java) { PhoneLogin.format("886", "1234567890123") }
        assertThrows(IllegalArgumentException::class.java) { PhoneLogin.format("1234", "912345678") }
        assertThrows(IllegalArgumentException::class.java) { PhoneLogin.format("886", "123") }
    }

    @Test fun parseOneString() {
        assertEquals("13800138000", PhoneLogin.parse("13800138000"))
        assertEquals("+886 912345678", PhoneLogin.parse("+886-0912345678"))
        assertEquals("+1 4155552671", PhoneLogin.parse("+1 4155552671"))
        assertThrows(IllegalArgumentException::class.java) { PhoneLogin.parse("+8860912345678") }
    }
}
