package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import org.junit.Assert.*
import org.junit.Test
class AnchorGeometryTest{
 @Test fun geometry(){val d=AnchorGeometry.distanceMeters(-36.8485,174.7633,-36.8475,174.7633);assertEquals(111.0,d,1.0);assertEquals(0.0,AnchorGeometry.bearingDegrees(-36.8485,174.7633,-36.8475,174.7633),.2);val p=AnchorGeometry.project(-36.8485,174.7633,90.0,100.0);assertEquals(100.0,AnchorGeometry.distanceMeters(-36.8485,174.7633,p.first,p.second),.2)}
 @Test fun rode(){assertEquals(5.0,AnchorGeometry.scope(30.0,5.0,1.0)!!,.001);assertEquals(kotlin.math.sqrt(30.0*30-6.0*6)+2,AnchorGeometry.expectedRadius(30.0,5.0,1.0,2.0),.001)}
}
