package app.rewit.mobile

/**
 * Estados possíveis da permissão de câmera expostos pelo canal nativo Android.
 */
enum class CameraPermissionStatus(val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    PERMANENTLY_DENIED("permanentlyDenied"),
}

/**
 * Resultado bruto reportado por [android.app.Activity.onRequestPermissionsResult].
 */
enum class CameraPermissionRequestOutcome {
    GRANTED,
    DENIED,
    CANCELLED,
}

/**
 * Snapshot persistido em `SharedPreferences` com o histórico real de decisões.
 *
 * [hasRequestedBefore] só é marcado após uma decisão explícita concluída (negação
 * não cancelada ou sinal de rationale do Android) e é limpo quando a permissão
 * passa a ser concedida, evitando que a primeira solicitação ou um cancelamento
 * seja classificado prematuramente como negação permanente.
 */
data class CameraPermissionPersistedState(
    val hasRequestedBefore: Boolean = false,
    val permanentlyDenied: Boolean = false,
)

/**
 * Resultado de uma transição da máquina de estados de permissão de câmera.
 */
data class CameraPermissionTransition(
    val status: CameraPermissionStatus,
    val nextState: CameraPermissionPersistedState,
)

/**
 * Máquina de estados pura para classificar o estado da permissão de câmera Android
 * combinando estado atual do sistema, `shouldShowRequestPermissionRationale`,
 * resultado real de `onRequestPermissionsResult` e histórico persistido.
 */
object CameraPermissionStateMachine {
    fun resolveStatusCheck(
        state: CameraPermissionPersistedState,
        isGranted: Boolean,
        shouldShowRationale: Boolean,
        afterRequest: Boolean,
    ): CameraPermissionTransition {
        if (isGranted) {
            return CameraPermissionTransition(
                status = CameraPermissionStatus.GRANTED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = false,
                    permanentlyDenied = false,
                ),
            )
        }

        if (shouldShowRationale) {
            // Se o Android recomenda exibir justificativa, ainda é possível solicitar novamente.
            // O próprio rationale positivo confirma que já houve uma solicitação/negação prévia.
            return CameraPermissionTransition(
                status = CameraPermissionStatus.DENIED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = true,
                    permanentlyDenied = false,
                ),
            )
        }

        if (afterRequest && state.hasRequestedBefore) {
            return CameraPermissionTransition(
                status = CameraPermissionStatus.PERMANENTLY_DENIED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = true,
                    permanentlyDenied = true,
                ),
            )
        }

        val status = if (state.permanentlyDenied) {
            CameraPermissionStatus.PERMANENTLY_DENIED
        } else {
            CameraPermissionStatus.DENIED
        }
        return CameraPermissionTransition(
            status = status,
            nextState = state,
        )
    }

    fun resolveRequestResult(
        state: CameraPermissionPersistedState,
        outcome: CameraPermissionRequestOutcome,
        isGrantedAfterResult: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionTransition {
        if (outcome == CameraPermissionRequestOutcome.GRANTED || isGrantedAfterResult) {
            return CameraPermissionTransition(
                status = CameraPermissionStatus.GRANTED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = false,
                    permanentlyDenied = false,
                ),
            )
        }

        if (outcome == CameraPermissionRequestOutcome.CANCELLED) {
            // Diálogo cancelado (`grantResults.isEmpty()`): não registra solicitação concluída
            // nem promove para negação permanente.
            val permanentlyDenied = if (shouldShowRationale) false else state.permanentlyDenied
            return CameraPermissionTransition(
                status = if (permanentlyDenied) {
                    CameraPermissionStatus.PERMANENTLY_DENIED
                } else {
                    CameraPermissionStatus.DENIED
                },
                nextState = state.copy(permanentlyDenied = permanentlyDenied),
            )
        }

        if (shouldShowRationale) {
            return CameraPermissionTransition(
                status = CameraPermissionStatus.DENIED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = true,
                    permanentlyDenied = false,
                ),
            )
        }

        if (!state.hasRequestedBefore) {
            // Na primeira solicitação concluída, `shouldShowRationale == false` pode ocorrer por
            // cancelamento ou particularidade do diálogo nativo; mantém como `denied` e registra
            // apenas o histórico de solicitação concluída para a avaliação da próxima tentativa.
            return CameraPermissionTransition(
                status = CameraPermissionStatus.DENIED,
                nextState = CameraPermissionPersistedState(
                    hasRequestedBefore = true,
                    permanentlyDenied = false,
                ),
            )
        }

        return CameraPermissionTransition(
            status = CameraPermissionStatus.PERMANENTLY_DENIED,
            nextState = CameraPermissionPersistedState(
                hasRequestedBefore = true,
                permanentlyDenied = true,
            ),
        )
    }
}
