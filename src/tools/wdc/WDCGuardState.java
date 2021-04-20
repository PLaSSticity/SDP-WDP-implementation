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
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.wdc.event.ShadowID;

import java.util.*;

public class WDCGuardState implements ShadowVar {
	public static final int NO_LAST_WRITER = -1;
	private final ShadowID id = new ShadowID();

	public volatile CV hbRead;
	public CV hbReadsJoined;
	public volatile CVE hbWrite;
	public CV wcpRead;
	public CV wcpReadsJoined;
	public CV wcpWrite;
	public CV nwcRead;
	public CV nwcReadsJoined;
	public CV nwcWrite;
	public CV nwcWritesJoined;
	public CV wdcRead; // for each tid, the clock of the thread when it last read the variable
	public CV wdcReadsJoined; // the join of all prior reads
	public CV wdcWrite; // the time (the thread's full VC) for the last write
	public CV wbrRead;
	public CV wbrReadsJoined;
	public CV wbrWrite;
	public int lastWriteTid; // the thread of the last write

	public DynamicSourceLocation[] lastReadEvents; 
	public DynamicSourceLocation[] lastWriteEvents;
	public final int[] lastReadLamport = new int[RR.maxTidOption.get()];
	public final int[] lastWriteLamport = new int[RR.maxTidOption.get()];

	int lastAccessEventNumber = -3;

	// Read locksets
	public HashMap<Integer/*tid*/,Collection<ShadowLock>> heldLocksRead;
	// Write locksets
	public HashMap<Integer/*tid*/,Collection<ShadowLock>> heldLocksWrite;

	public WDCGuardState() {
		if (WDCTool.hasHB) {
			hbRead = new CV(WDCTool.INIT_CV_SIZE);
			hbReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			hbWrite = new CVE(WDCTool.INIT_CV_SIZE, null);
		}
		if (WDCTool.hasWCP) {
			wcpRead = new CV(WDCTool.INIT_CV_SIZE);
			wcpReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wcpWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasNWC) {
			 nwcRead = new CV(WDCTool.INIT_CV_SIZE);
			 nwcReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			 nwcWrite = new CV(WDCTool.INIT_CV_SIZE);
			 nwcWritesJoined = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasDC) {
			wdcRead = new CV(WDCTool.INIT_CV_SIZE);
			wdcReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wdcWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasWBR) {
			wbrRead = new CV(WDCTool.INIT_CV_SIZE);
			wbrReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			wbrWrite = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.hasWBR) {
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
		return String.format("[HBw=%s HBr=%s WCPw=%s WCPr=%s NWCw=%s NWCr=%s WDCw=%s WDCr=%s]", hbWrite, hbRead, wcpWrite, wcpRead, nwcWrite, nwcRead, wdcWrite, wdcRead);
	}

	public final ShadowID getID() {
		return id;
	}

	public final boolean hasLastWriter() {
		return lastWriteTid != NO_LAST_WRITER;
	}
}
