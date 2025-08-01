/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.common.util

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import org.apache.commons.lang3.SystemUtils

/**
 * Console font information structure for Windows API.
 * Must be public for JNA reflection access.
 */
class ConsoleFontInfoEx : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var nFont: Int = 0
    @JvmField var dwFontSize: Coord = Coord()
    @JvmField var FontFamily: Int = 0
    @JvmField var FontWeight: Int = 0
    @JvmField var FaceName: CharArray = CharArray(32)

    class Coord : Structure() {
        @JvmField var X: Short = 0
        @JvmField var Y: Short = 0
        override fun getFieldOrder(): List<String> = listOf("X", "Y")
    }

    override fun getFieldOrder(): List<String> =
        listOf("cbSize", "nFont", "dwFontSize", "FontFamily", "FontWeight", "FaceName")
}

/**
 * Extended Kernel32 interface for console font operations.
 * Must be public for JNA access.
 */
interface Kernel32Extended : Kernel32 {
    companion object {
        val INSTANCE: Kernel32Extended? by lazy {
            if (SystemUtils.IS_OS_WINDOWS) {
                try {
                    Native.load("kernel32", Kernel32Extended::class.java)
                } catch (e: Exception) {
                    println("Failed to load kernel32 extended functions: ${e.message}")
                    null
                }
            } else {
                null
            }
        }
    }

    fun SetCurrentConsoleFontEx(
        hConsoleOutput: WinNT.HANDLE,
        bMaximumWindow: Boolean,
        lpConsoleCurrentFontEx: ConsoleFontInfoEx
    ): Boolean

    fun GetCurrentConsoleFontEx(
        hConsoleOutput: WinNT.HANDLE,
        bMaximumWindow: Boolean,
        lpConsoleCurrentFontEx: ConsoleFontInfoEx
    ): Boolean
}

/**
 * Utility class for Windows-specific operations.
 */
@ExcludeFromJacocoGeneratedReport(reason = "Windows-specific operations")
class WinUtils {

    companion object {
        const val UTF8_CODEPAGE = 65001

        /**
         * Returns true if the current OS is Windows.
         */
        @kotlin.jvm.JvmStatic
        fun IS_OS_WINDOWS(): Boolean {
            return SystemUtils.IS_OS_WINDOWS
        }

        /**
         * Sets the console code page to UTF-8.
         */
        @kotlin.jvm.JvmStatic
        fun CHCP_TO_UTF8() { //NOSONAR
            Kernel32.INSTANCE.SetConsoleCP(UTF8_CODEPAGE)
            Kernel32.INSTANCE.SetConsoleOutputCP(UTF8_CODEPAGE)
            println("Active Console Code Page: ${Kernel32.INSTANCE.GetConsoleCP()}")
        }

        /**
         * Returns the active console code page.
         */
        @kotlin.jvm.JvmStatic
        fun ACTIVE_CONSOLE_CODEPAGE(): Int { //NOSONAR
            return Kernel32.INSTANCE.GetConsoleCP()
        }

        /**
         * Returns true if the ASCII table is supported.
         */
        @kotlin.jvm.JvmStatic
        fun ASCII_TABLE_SUPPORTED(): Boolean {
            if (IS_OS_WINDOWS()) {
                return UTF8_CODEPAGE == ACTIVE_CONSOLE_CODEPAGE()
            }
            //if not windows then it is supported regardless of what the code page is
            return true
        }

        /**
         * Sets the console font to Cascadia Code with UTF-8 support.
         * Automatically switches to UTF-8 code page for optimal Unicode display.
         * Preserves the original console font size unless overridden.
         *
         * @param fontSize Optional font size (if null, preserves current size)
         * @return true if font was successfully changed, false otherwise
         */
        @kotlin.jvm.JvmStatic
        fun SET_CASCADIA_CODE_FONT(fontSize: Short? = null): Boolean { //NOSONAR
            if (!IS_OS_WINDOWS()) {
                println("Font switching only supported on Windows")
                return false
            }

            val kernel32Extended = Kernel32Extended.INSTANCE
            if (kernel32Extended == null) {
                println("Windows font APIs not available")
                return false
            }

            return try {
                // First ensure UTF-8 support
                CHCP_TO_UTF8()

                // Get console handle
                val consoleHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE)
                if (consoleHandle == null || consoleHandle.equals(WinNT.INVALID_HANDLE_VALUE)) {
                    println("Failed to get console handle")
                    return false
                }

                // Get current font info
                val fontInfo = ConsoleFontInfoEx()
                fontInfo.cbSize = fontInfo.size()

                if (!kernel32Extended.GetCurrentConsoleFontEx(consoleHandle, false, fontInfo)) {
                    println("Failed to get current font info")
                    return false
                }

                // Preserve original size if no specific size requested
                val targetFontSize = fontSize ?: fontInfo.dwFontSize.Y

                // Set Cascadia Code font
                fontInfo.apply {
                    cbSize = size()
                    FaceName.fill('\u0000')
                    "Cascadia Code".toCharArray().copyInto(
                        FaceName,
                        endIndex = minOf("Cascadia Code".length, FaceName.size - 1)
                    )
                    dwFontSize.X = 0 // Width (0 = default)
                    dwFontSize.Y = targetFontSize
                    FontWeight = 400 // Normal weight
                }

                val success = kernel32Extended.SetCurrentConsoleFontEx(consoleHandle, false, fontInfo)
                if (success) {
                    val sizeMsg = if (fontSize == null) "size preserved: $targetFontSize" else "size: $targetFontSize"
                    println("✓ Console font changed to Cascadia Code ($sizeMsg)")
                } else {
                    println("✗ Failed to change console font to Cascadia Code")
                }
                success

            } catch (e: Exception) {
                println("Error changing font: ${e.message}")
                false
            }
        }

        /**
         * Sets up optimal console for Unicode display.
         * Tries Cascadia Code first, then falls back to other suitable fonts.
         * Preserves the original console font size.
         *
         * @return true if any suitable font was successfully applied
         */
        @kotlin.jvm.JvmStatic
        fun SETUP_OPTIMAL_CONSOLE(): Boolean { //NOSONAR
            if (!IS_OS_WINDOWS()) {
                println("Console optimization only supported on Windows")
                return false
            }

            val kernel32Extended = Kernel32Extended.INSTANCE
            if (kernel32Extended == null) {
                println("Windows font APIs not available")
                return false
            }

            // Get current font size before making changes
            val originalFontSize = getCurrentConsoleFontSize(kernel32Extended) ?: 16

            // Try fonts in order of preference
            val fonts = arrayOf("Cascadia Code", "Cascadia Mono", "Consolas")

            for (font in fonts) {
                if (setConsoleFont(font, kernel32Extended, originalFontSize)) {
                    println("✓ Console optimized with $font font (size preserved: $originalFontSize)")
                    return true
                }
            }

            println("✗ No suitable Unicode fonts found")
            return false
        }

        /**
         * Helper function to get the current console font size.
         *
         * @param kernel32Extended The extended kernel32 interface
         * @return Current font size, or null if unable to retrieve
         */
        private fun getCurrentConsoleFontSize(kernel32Extended: Kernel32Extended): Short? {
            return try {
                val consoleHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE)
                if (consoleHandle == null || consoleHandle.equals(WinNT.INVALID_HANDLE_VALUE)) {
                    return null
                }

                val fontInfo = ConsoleFontInfoEx()
                fontInfo.cbSize = fontInfo.size()

                if (kernel32Extended.GetCurrentConsoleFontEx(consoleHandle, false, fontInfo)) {
                    fontInfo.dwFontSize.Y
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Private helper to set any console font with preserved or specified size.
         */
        private fun setConsoleFont(fontName: String, kernel32Extended: Kernel32Extended, fontSize: Short): Boolean {
            return try {
                CHCP_TO_UTF8()

                val consoleHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE)
                if (consoleHandle == null || consoleHandle.equals(WinNT.INVALID_HANDLE_VALUE)) {
                    return false
                }

                val fontInfo = ConsoleFontInfoEx()
                fontInfo.cbSize = fontInfo.size()

                if (!kernel32Extended.GetCurrentConsoleFontEx(consoleHandle, false, fontInfo)) {
                    return false
                }

                fontInfo.apply {
                    cbSize = size()
                    FaceName.fill('\u0000')
                    fontName.toCharArray().copyInto(
                        FaceName,
                        endIndex = minOf(fontName.length, FaceName.size - 1)
                    )
                    dwFontSize.X = 0
                    dwFontSize.Y = fontSize // Use the preserved size
                    FontWeight = 400
                }

                kernel32Extended.SetCurrentConsoleFontEx(consoleHandle, false, fontInfo)

            } catch (e: Exception) {
                false
            }
        }
    }
}
