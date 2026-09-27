package com.securechat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Small helper so screens can construct a ViewModel with manually-wired dependencies. */
@Composable
inline fun <reified VM : ViewModel> rememberViewModelFactory(
    crossinline create: @DisallowComposableCalls () -> VM,
): ViewModelProvider.Factory =
    remember {
        viewModelFactory {
            initializer { create() }
        }
    }
