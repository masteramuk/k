// Copyright (c) 2014-2016 K Team. All Rights Reserved.
module MINIMUM-SPEC
  imports KERNELC

rule
  <fun>... FUN:Map ...</fun>
  <k>
    minimum(tv(int, X:Int), tv(int, Y:Int))
  =>
    tv(int, minInt(X, Y))
  ...</k>
  <in> IN:List </in>
  <out> OUT:List </out>

endmodule
