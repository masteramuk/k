// Copyright (c) 2014-2016 K Team. All Rights Reserved.
module COMM-ASSOC-SPEC
  imports KERNELC

rule
  <fun>... FUN:Map ...</fun>
  <k>
    comm_assoc(tv(int, X:Int), tv(int, Y:Int), tv(int, Z:Int))
  =>
    tv(int, 1)
  ...</k>
  <in> IN:List </in>
  <out> OUT:List </out>

endmodule
