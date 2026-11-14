package app.rewit.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionStateMachineTest {

    @Test
    fun `nunca solicitada retorna denied sem marcar historico ou negacao permanente`() {
        val initial = CameraPermissionPersistedState()

        val passiveCheck = CameraPermissionStateMachine.resolveStatusCheck(
            state = initial,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, passiveCheck.status)
        assertFalse(passiveCheck.nextState.hasRequestedBefore)
        assertFalse(passiveCheck.nextState.permanentlyDenied)

        val checkAfterErrorWithoutPriorRequest = CameraPermissionStateMachine.resolveStatusCheck(
            state = initial,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = true,
        )
        assertEquals(CameraPermissionStatus.DENIED, checkAfterErrorWithoutPriorRequest.status)
        assertFalse(checkAfterErrorWithoutPriorRequest.nextState.hasRequestedBefore)
        assertFalse(checkAfterErrorWithoutPriorRequest.nextState.permanentlyDenied)
    }

    @Test
    fun `primeira solicitacao negada com rationale true retorna denied e registra historico`() {
        val transition = CameraPermissionStateMachine.resolveRequestResult(
            state = CameraPermissionPersistedState(),
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = true,
        )

        assertEquals(CameraPermissionStatus.DENIED, transition.status)
        assertTrue(transition.nextState.hasRequestedBefore)
        assertFalse(transition.nextState.permanentlyDenied)
    }

    @Test
    fun `primeira solicitacao negada com rationale false nao marca negacao permanente prematura`() {
        val firstDenial = CameraPermissionStateMachine.resolveRequestResult(
            state = CameraPermissionPersistedState(),
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionStatus.DENIED, firstDenial.status)
        assertTrue(firstDenial.nextState.hasRequestedBefore)
        assertFalse(firstDenial.nextState.permanentlyDenied)

        val subsequentCheck = CameraPermissionStateMachine.resolveStatusCheck(
            state = firstDenial.nextState,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, subsequentCheck.status)
        assertFalse(subsequentCheck.nextState.permanentlyDenied)

        // Se na segunda tentativa o Android ainda exibir dialogo e passar rationale = true, continua denied.
        val secondDenialWithRationale = CameraPermissionStateMachine.resolveRequestResult(
            state = firstDenial.nextState,
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = true,
        )
        assertEquals(CameraPermissionStatus.DENIED, secondDenialWithRationale.status)
        assertFalse(secondDenialWithRationale.nextState.permanentlyDenied)
    }

    @Test
    fun `primeira solicitacao cancelada nao marca permanente nem suja historico de solicitacao`() {
        val cancelled = CameraPermissionStateMachine.resolveRequestResult(
            state = CameraPermissionPersistedState(),
            outcome = CameraPermissionRequestOutcome.CANCELLED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionStatus.DENIED, cancelled.status)
        assertFalse(cancelled.nextState.hasRequestedBefore)
        assertFalse(cancelled.nextState.permanentlyDenied)

        // Como a primeira tentativa foi cancelada (grantResults vazio), a proxima negacao sem
        // rationale ainda deve ser tratada como primeira solicitacao concluida.
        val nextDenialWithoutRationale = CameraPermissionStateMachine.resolveRequestResult(
            state = cancelled.nextState,
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, nextDenialWithoutRationale.status)
        assertTrue(nextDenialWithoutRationale.nextState.hasRequestedBefore)
        assertFalse(nextDenialWithoutRationale.nextState.permanentlyDenied)
    }

    @Test
    fun `cancelamento apos negacao temporaria anterior tambem nao promove para permanente`() {
        val afterFirstDenial = CameraPermissionPersistedState(
            hasRequestedBefore = true,
            permanentlyDenied = false,
        )

        val cancelledSecondPrompt = CameraPermissionStateMachine.resolveRequestResult(
            state = afterFirstDenial,
            outcome = CameraPermissionRequestOutcome.CANCELLED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionStatus.DENIED, cancelledSecondPrompt.status)
        assertTrue(cancelledSecondPrompt.nextState.hasRequestedBefore)
        assertFalse(cancelledSecondPrompt.nextState.permanentlyDenied)
    }

    @Test
    fun `segunda solicitacao negada com rationale false evolui para permanentlyDenied`() {
        val afterFirstDenial = CameraPermissionStateMachine.resolveRequestResult(
            state = CameraPermissionPersistedState(),
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = true,
        ).nextState

        val secondDenial = CameraPermissionStateMachine.resolveRequestResult(
            state = afterFirstDenial,
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )

        assertEquals(CameraPermissionStatus.PERMANENTLY_DENIED, secondDenial.status)
        assertTrue(secondDenial.nextState.hasRequestedBefore)
        assertTrue(secondDenial.nextState.permanentlyDenied)

        val persistedCheck = CameraPermissionStateMachine.resolveStatusCheck(
            state = secondDenial.nextState,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.PERMANENTLY_DENIED, persistedCheck.status)
    }

    @Test
    fun `concessao apos negacao anterior ou permanente retorna granted e limpa estado persistido`() {
        val permanentlyDeniedState = CameraPermissionPersistedState(
            hasRequestedBefore = true,
            permanentlyDenied = true,
        )

        val grantedViaPrompt = CameraPermissionStateMachine.resolveRequestResult(
            state = CameraPermissionPersistedState(
                hasRequestedBefore = true,
                permanentlyDenied = false,
            ),
            outcome = CameraPermissionRequestOutcome.GRANTED,
            isGrantedAfterResult = true,
            shouldShowRationale = false,
        )
        assertEquals(CameraPermissionStatus.GRANTED, grantedViaPrompt.status)
        assertFalse(grantedViaPrompt.nextState.hasRequestedBefore)
        assertFalse(grantedViaPrompt.nextState.permanentlyDenied)

        val grantedViaSettings = CameraPermissionStateMachine.resolveStatusCheck(
            state = permanentlyDeniedState,
            isGranted = true,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.GRANTED, grantedViaSettings.status)
        assertFalse(grantedViaSettings.nextState.hasRequestedBefore)
        assertFalse(grantedViaSettings.nextState.permanentlyDenied)
    }

    @Test
    fun `revogacao nas configuracoes apos concessao e retorno reavalia estado real sem falso positivo`() {
        val permanentlyDeniedState = CameraPermissionPersistedState(
            hasRequestedBefore = true,
            permanentlyDenied = true,
        )

        // 1. Usuario concede nas Configuracoes e retorna ao app.
        val returnedGranted = CameraPermissionStateMachine.resolveStatusCheck(
            state = permanentlyDeniedState,
            isGranted = true,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.GRANTED, returnedGranted.status)
        assertFalse(returnedGranted.nextState.hasRequestedBefore)
        assertFalse(returnedGranted.nextState.permanentlyDenied)

        // 2. Usuario revoga a permissao nas Configuracoes e retorna ao app.
        val returnedRevoked = CameraPermissionStateMachine.resolveStatusCheck(
            state = returnedGranted.nextState,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, returnedRevoked.status)
        assertFalse(returnedRevoked.nextState.permanentlyDenied)

        // 3. Mesmo que a primeira nova tentativa apos revogacao retorne DENIED com rationale = false,
        // nao deve classificar prematuramente como permanente.
        val postRevocationFirstDenial = CameraPermissionStateMachine.resolveRequestResult(
            state = returnedRevoked.nextState,
            outcome = CameraPermissionRequestOutcome.DENIED,
            isGrantedAfterResult = false,
            shouldShowRationale = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, postRevocationFirstDenial.status)
        assertFalse(postRevocationFirstDenial.nextState.permanentlyDenied)
    }

    @Test
    fun `retorno das configuracoes sem conceder mantem estado real coerente`() {
        val permanentlyDeniedState = CameraPermissionPersistedState(
            hasRequestedBefore = true,
            permanentlyDenied = true,
        )

        val stillDenied = CameraPermissionStateMachine.resolveStatusCheck(
            state = permanentlyDeniedState,
            isGranted = false,
            shouldShowRationale = false,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.PERMANENTLY_DENIED, stillDenied.status)
        assertTrue(stillDenied.nextState.permanentlyDenied)

        // Se o Android voltar a reportar shouldShowRationale = true, limpa permanentDenied.
        val rationaleRestored = CameraPermissionStateMachine.resolveStatusCheck(
            state = permanentlyDeniedState,
            isGranted = false,
            shouldShowRationale = true,
            afterRequest = false,
        )
        assertEquals(CameraPermissionStatus.DENIED, rationaleRestored.status)
        assertFalse(rationaleRestored.nextState.permanentlyDenied)
        assertTrue(rationaleRestored.nextState.hasRequestedBefore)
    }
}
