package com.example.scanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.scanner.ui.theme.ScannerTheme
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : ComponentActivity() {

    private lateinit var scannerResultLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private var scanResultCallback: ((String) -> Unit)? = null
    private lateinit var dbReference: DatabaseReference
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbReference = FirebaseDatabase.getInstance().reference.child("codigo")

        // Registrar el ActivityResultLauncher
        scannerResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val scanResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
            if (scanResult != null) {
                if (scanResult.contents == null) {
                    // Manejar cancelación del escaneo
                } else {
                    // Manejar resultado del escaneo
                    scanResultCallback?.invoke(scanResult.contents)
                }
            }
        }

        setContent {
            ScannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScannerScreen(modifier = Modifier.padding(innerPadding), startScanner = {
                        startScanner(it)
                    })
                }
            }
        }
    }

    private fun initiateScanner() {
        IntentIntegrator(this).apply {
            setBeepEnabled(false) // Opcional: desactivar el beep al escanear
            setOrientationLocked(false) // Opcional: desbloquear orientación
            scannerResultLauncher.launch(createScanIntent())
        }
    }

    private fun startScanner(onScanResult: (String) -> Unit) {
        scanResultCallback = onScanResult
        initiateScanner()
    }

    private fun sendToRealtimeDatabase(data: String) {
        val dataMap = mapOf("value" to data, "timestamp" to System.currentTimeMillis().toString())
        dbReference.setValue(dataMap)
    }

    private fun fetchProductDetails(codigo: String, onResult: (ProductDetails?) -> Unit) {
        firestore.collection("inventario").whereEqualTo("codigo", codigo).get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    onResult(null)
                } else {
                    val productData = documents.first().toObject(ProductDetails::class.java)
                    onResult(productData)
                }
            }
            .addOnFailureListener {
                onResult(null)
            }
    }
}

@Composable
fun ScannerScreen(modifier: Modifier = Modifier, startScanner: (onScanResult: (String) -> Unit) -> Unit) {
    var scanResult by remember { mutableStateOf("") }
    var productDetails by remember { mutableStateOf<ProductDetails?>(null) }
    var manualCode by remember { mutableStateOf(TextFieldValue("")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current as ComponentActivity

    Column(modifier = modifier.padding(16.dp)) {
        Button(
            onClick = {
                startScanner { result ->
                    scanResult = result
                    sendToRealtimeDatabase(context, result)
                    fetchProductDetails(context, result) { details ->
                        productDetails = details
                        loading = false
                        showDialog = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Escanear con Cámara")
        }
        OutlinedTextField(
            value = manualCode,
            onValueChange = { manualCode = it },
            label = { Text("Ingresar código Manual") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        Button(
            onClick = {
                if (manualCode.text.trim().isNotEmpty()) {
                    scanResult = manualCode.text
                    sendToRealtimeDatabase(context, manualCode.text)
                    fetchProductDetails(context, manualCode.text) { details ->
                        productDetails = details
                        loading = false
                        showDialog = true
                    }
                } else {
                    error = "Por favor, ingrese un código válido."
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Buscar")
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
        }

        if (scanResult.isNotEmpty() && !loading) {
            Text("Código escaneado: $scanResult", modifier = Modifier.padding(vertical = 8.dp))
        }

        productDetails?.let {
            if (showDialog) {
                ProductDetailsDialog(productDetails = it) {
                    showDialog = false
                    scanResult = ""
                    productDetails = null
                    manualCode = TextFieldValue("")
                    sendToRealtimeDatabase(context, "")
                }
            }
        } ?: run {
            if (!loading && scanResult.isNotEmpty()) {
                Text("No se encontraron detalles del producto para este código.", modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        error?.let {
            AlertDialog(
                onDismissRequest = { error = null },
                confirmButton = {
                    Button(onClick = { error = null }) {
                        Text("OK")
                    }
                },
                title = { Text("Error") },
                text = { Text(it) }
            )
        }
    }
}
@Composable
fun ProductDetailsDialog(productDetails: ProductDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        confirmButton = {
            Button(
                onClick = { onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Listo")
            }
        },
        title = { Text("Detalles del Producto", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailRow("Nombre:", productDetails.nombre)
                DetailRow("Categoría:", productDetails.categoria)
                DetailRow("Código:", productDetails.codigo)
                DetailRow("Precio:", productDetails.precio.toString())
                DetailRow("Cantidad:", productDetails.cantidad.toString())
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


fun sendToRealtimeDatabase(context: ComponentActivity, data: String) {
    val dbReference = FirebaseDatabase.getInstance().reference.child("codigo")
    val dataMap = mapOf("value" to data, "timestamp" to System.currentTimeMillis().toString())
    dbReference.setValue(dataMap)
}

fun fetchProductDetails(context: ComponentActivity, codigo: String, onResult: (ProductDetails?) -> Unit) {
    val firestore = FirebaseFirestore.getInstance()
    firestore.collection("inventario").whereEqualTo("codigo", codigo).get()
        .addOnSuccessListener { documents ->
            if (documents.isEmpty) {
                onResult(null)
            } else {
                val productData = documents.first().toObject(ProductDetails::class.java)
                onResult(productData)
            }
        }
        .addOnFailureListener {
            onResult(null)
        }
}

data class ProductDetails(
    val nombre: String = "",
    val categoria: String = "",
    val codigo: String = "",
    val precio: Long = 0L, // Cambiado a Long
    val cantidad: Int = 0
)
