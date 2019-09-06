/******************************************************************************

Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz)
                    and Stephen Freund (Williams College) 

All rights reserved.  

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

 * Neither the names of the University of California, Santa Cruz
      and Williams College nor the names of its contributors may be
      used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 ******************************************************************************/

package tools.wdc;

import rr.state.ShadowVolatile;
import rr.tool.RR;
import tools.wdc.event.ShadowID;
import tools.wdc.event.VolatileRdWrNode;

import static tools.wdc.WDCGuardState.NO_LAST_WRITER;

public class WDCVolatileData {
	private final ShadowID id = new ShadowID();

	public final ShadowVolatile peer;
	public CV hbRead;
	public CV hbWrite;
	public CV wcpRead;
	public CV wcpWrite;
	public CV nwcRead;
	public CV nwcWrite;
	public CV wdcRead;
	public CV wdcWrite;
	public CVE udp;
	public CVE wbr;
	public CV lshe;
	
	public CV wbrWrites;
	public CV udpWrites;
	public VolatileRdWrNode[] lastWriteEvents;
	public int lastWriter = NO_LAST_WRITER;

	public int lastAccessEventNumber = -4;
	
	public WDCVolatileData(ShadowVolatile ld) {
		this.peer = ld;
		if (WDCTool.hasHB) {
			this.hbRead = new CV(WDCTool.INIT_CV_SIZE);
			this.hbWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasWCP) {
			this.wcpRead = new CV(WDCTool.INIT_CV_SIZE);
			this.wcpWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasNWC) {
			this.nwcRead = new CV(WDCTool.INIT_CV_SIZE);
			this.nwcWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasDC) {
			this.wdcRead = new CV(WDCTool.INIT_CV_SIZE);
			this.wdcWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasWBR) {
			this.wbr = new CVE(new CV(WDCTool.INIT_CV_SIZE), null);
			this.wbrWrites = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasUDP) {
			this.udp = new CVE(new CV(WDCTool.INIT_CV_SIZE), null);
			this.udpWrites = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasLSHE) {
			this.lshe = new CV(WDCTool.INIT_CV_SIZE);
		}
		this.lastWriteEvents = new VolatileRdWrNode[RR.maxTidOption.get()];
	}

	@Override
	public String toString() {
		return String.format("[HB=%s | %s] [WCP=%s | %s] [WDC=%s | %s]", hbRead, hbWrite, wcpRead, wcpWrite, wdcRead, wdcWrite);
	}

	public final ShadowID getID() {
		return id;
	}

	public final boolean hasLastWriter() {
		return lastWriter != NO_LAST_WRITER;
	}
}
