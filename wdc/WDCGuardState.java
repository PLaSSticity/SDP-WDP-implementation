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

import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.wdc.event.ShadowID;

import java.util.*;

public class WDCGuardState implements ShadowVar {
	public static final int NO_LAST_WRITER = -1;
	private final ShadowID id = new ShadowID();

	public volatile CV hbRead;
	public CV hbReadsJoined;
	public volatile CV hbWrite;
	public CV wcpRead;
	public CV wcpReadsJoined;
	public CV wcpWrite;
	public CV wdcRead; // for each tid, the clock of the thread when it last read the variable
	public CV wdcReadsJoined; // the join of all prior reads
	public CV wdcWrite; // the time (the thread's full VC) for the last write
	public CV wbrRead;
	public CV wbrReadsJoined;
	public CV wbrWrite;
	public CV lsheRead;
	public CV lsheReadsJoined;
	public CV lsheWrite;
	public int lastWriteTid; // the thread of the last write

	public DynamicSourceLocation[] lastReadEvents; 
	public DynamicSourceLocation[] lastWriteEvents;

	// Read locksets
	public HashMap<Integer/*tid*/,Collection<ShadowLock>> heldLocksRead;
	// Write locksets
	public HashMap<Integer/*tid*/,Collection<ShadowLock>> heldLocksWrite;

	public WDCGuardState() {
		if (WDCTool.HB || WDCTool.WCP || WDCTool.HB_WCP_DC || WDCTool.WCP_WBR || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			hbRead = new CV(WDCTool.INIT_CV_SIZE);
			hbReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			hbWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.WCP || WDCTool.HB_WCP_DC || WDCTool.WCP_WBR || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			wcpRead = new CV(WDCTool.INIT_CV_SIZE);
			wcpReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wcpWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.DC || WDCTool.HB_WCP_DC || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			wdcRead = new CV(WDCTool.INIT_CV_SIZE);
			wdcReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wdcWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.WBR || WDCTool.WCP_WBR || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			wbrRead = new CV(WDCTool.INIT_CV_SIZE);
			wbrReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wbrWrite = new CV(WDCTool.INIT_CV_SIZE);

			heldLocksRead = new HashMap<>();
			heldLocksWrite = new HashMap<>();
		}
		if (WDCTool.WCP_DC_WBR_LSHE) {
			lsheRead = new CV(WDCTool.INIT_CV_SIZE);
			lsheReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			lsheWrite = new CV(WDCTool.INIT_CV_SIZE);
			
			heldLocksRead = new HashMap<>();
			heldLocksWrite = new HashMap<>();
		}
		
		
		//TODO: Why are these needed for each analysis?
		lastWriteTid = NO_LAST_WRITER;
		lastReadEvents = new DynamicSourceLocation[RR.maxTidOption.get()];
		lastWriteEvents = new DynamicSourceLocation[RR.maxTidOption.get()];
	}

	@Override
	public String toString() {
		return String.format("[HBw=%s HBr=%s WCPw=%s WCPr=%s WDCw=%s WDCr=%s]", hbWrite, hbRead, wcpWrite, wcpRead, wdcWrite, wdcRead);
	}

	public final ShadowID getID() {
		return id;
	}

	public final boolean hasLastWriter() {
		return lastWriteTid != NO_LAST_WRITER;
	}
}
