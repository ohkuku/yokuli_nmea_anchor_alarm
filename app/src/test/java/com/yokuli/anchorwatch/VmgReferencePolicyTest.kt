package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.VmgReferencePolicy
import org.junit.Assert.*
import org.junit.Test

class VmgReferencePolicyTest{
    @Test fun waterReferencedVmgWinsWhenStwAndTwaExist(){val result=VmgReferencePolicy.calculate(6.0,60.0,9.0,0.0,180.0)!!;assertEquals(3.0,result.knots,.001);assertTrue(result.provenance.startsWith("STW"))}
    @Test fun completeGroundVectorIsTheOnlyFallback(){assertNull(VmgReferencePolicy.calculate(null,45.0,6.0,90.0,null));val result=VmgReferencePolicy.calculate(null,45.0,6.0,90.0,0.0)!!;assertEquals(0.0,result.knots,.001);assertTrue(result.provenance.startsWith("SOG"))}
}
