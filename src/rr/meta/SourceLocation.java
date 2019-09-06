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

package rr.meta;

import java.io.Serializable;
import java.util.regex.Pattern;

import acme.util.Util;

public class SourceLocation implements Serializable, Comparable<SourceLocation> {

	public static final SourceLocation NULL = new SourceLocation("?", null, -1, -1);
	
	protected final String file;
	protected final MethodInfo method;
	protected final int line;
	protected final int offset;

	// BR: Pre-computing source locations in the format we need
	// Full method name + offset
	private final String sourceLoc;
	// Used to remove "__$rr_" parts from the source location information, which are inserted by RoadRunner
	private final static Pattern rrInstPattern = Pattern.compile("__[$]rr_(.+)__[$]rr__Original_");
	private static String cleanSourceLocation(MethodInfo method) {
		if (method == null) return "";
		return rrInstPattern.matcher(method.computeKey()).replaceAll("$1");
	}
	// BR: Added user-friendly source locations (method + line + offset)
	private final String friendlySourceLoc;

	// WDC: Added method for WDC and Goldilocks projects (and BR)
	public SourceLocation(String file, MethodInfo method, int line, int offset) {
		this.file = file;
		this.method = method;
		this.line = line;
		this.offset = offset;

		final String cleanSourceLoc = cleanSourceLocation(method);
		this.sourceLoc = cleanSourceLoc + ":" + offset;
		this.friendlySourceLoc = cleanSourceLoc + ":" + line + ":" + offset;
	}

	/*private SourceLocation(String file, int line) {
		this(file, line, -1);
	}*/


	@Override
	public String toString() {
		// BR: Switched from keystring to pre-computed sourceLoc, because the string construction & interning at runtime was a bottleneck with the max racecount removed
		return sourceLoc;
	}

	public static String toKeyString(String file, int line, int offset) {
		return (file + ":" + line + (offset == -1 ? "" : ":" + offset)).intern();
	}

	public static String toKeyString(String file, int line) {
		return toKeyString(file,line,-1);
	}

	public String getKey() {
		return toKeyString(file, line, offset);
	}

	public String getFile() {
		return file;
	}
	
	public MethodInfo getMethod() {
		return method;
	}
	
	public int getLine() {
		return line;
	}
	
	public int getOffset() {
		return offset;
	}

	public final String getSourceLoc() {
		return sourceLoc;
	}

	public final String getFriendlySourceLoc() {
		return friendlySourceLoc;
	}

	public int compareTo(SourceLocation loc) {
		int x = file.compareTo(loc.file);
		if (x == 0) x = method.compareTo(loc.method);
		if (x == 0) x = line - loc.line;
		if (x == 0) x = offset - loc.offset;
		return x;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((file == null) ? 0 : file.hashCode());
		result = prime * result + ((method == null) ? 0 : method.hashCode());
		result = prime * result + line;
		result = prime * result + offset;
	
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		return this.compareTo((SourceLocation)obj) == 0;
	}
	
}
