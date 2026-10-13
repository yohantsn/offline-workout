package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineworkout.R

// Campo numérico do modo performance: 64dp de altura, dígito tabular 26sp,
// unidade ao lado. Vazio mostra "—", nunca "0".
@Composable
fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                textStyle = TextStyle(
                    fontSize = 26.sp,
                    fontFeatureSettings = "tnum"
                ),
                placeholder = { Text(text = placeholder ?: stringResource(R.string.numeric_empty)) },
                isError = isError,
                supportingText = supportingText?.let { text ->
                    { Text(text = text) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
