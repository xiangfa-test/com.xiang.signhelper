// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// Vendored from the Miuix example app (Apache-2.0), directory example/shared/src/commonMain/kotlin/component/effect
// in https://github.com/compose-miuix-ui/miuix — taken from the Apache-2.0 upstream rather than the GPL-3.0 fork.
// Unmodified except for: package rename, the two Miuix-blur helpers replaced with local equivalents,
// isInDarkTheme -> isSystemInDarkTheme, and shouldShowSplitPane copied into this package.
// When distributing, the Apache-2.0 license and these notices must be retained.


package com.xiang.signhelper.ui.effect

enum class DeviceType {
    PHONE,
    PAD,
}
