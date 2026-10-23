package com.rtbishop.look4sat.core.domain.repository

data class RadioProfileStoreStatus(
    val phase:Int=0,val progress:Int=0,val error:Int=0,val busy:Boolean=false,val readOnly:Boolean=false,
    val ioPending:Boolean=false,val used:Int=0,val capacity:Int=56,val generation:Long=0,val transaction:Long=0,
    val nextSlot:Int=0,val correctionStepHz:Long=10,val kind:Int=0,val added:Int=0,val replaced:Int=0,val deleted:Int=0,
    val dirty:Boolean=false
) {
    val waitingForConfirmation get()=phase==6
    val complete get()=phase==9
}
