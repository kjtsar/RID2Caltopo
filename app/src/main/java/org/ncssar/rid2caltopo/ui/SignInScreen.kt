package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme

@Composable
fun SignInScreen(onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onSignInClick) {
            Text("Sign In")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SignInScreenPreview() {
    RID2CaltopoTheme {
        SignInScreen {}
    }
}
