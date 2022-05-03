package org.jetbrains.java.decompiler.modules.decompiler.sforms;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionEdge;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionNode;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionsGraph;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.*;

import java.util.*;

import static org.jetbrains.java.decompiler.modules.decompiler.sforms.VarMapHolder.mergeMaps;

class SSAProcessor {

  private final boolean incrementOnUsage;
  private final boolean simplePhi;
  private final boolean trackFieldVars;
  private final boolean trackPhantomPPNodes;
  private final boolean trackPhantomExitNodes;
  private final boolean trackSsuVersions;
  private final boolean doLiveVariableAnalysisRound;
  private final boolean trackDirectAssignments;
  private final boolean blockFieldPropagation;
  @Deprecated
  private final boolean ssau;


  // node id, var, version
  final HashMap<String, SFormsFastMapDirect> inVarVersions = new HashMap<>();

  // node id, var, version (direct branch)
  final HashMap<String, SFormsFastMapDirect> outVarVersions = new HashMap<>();

  // node id, var, version (negative branch)
  final HashMap<String, SFormsFastMapDirect> outNegVarVersions = new HashMap<>();

  // node id, var, version
  final HashMap<String, SFormsFastMapDirect> extraVarVersions = new HashMap<>();

  // var, version
  final HashMap<Integer, Integer> lastversion = new HashMap<>();

  // set factory
  FastSparseSetFactory<Integer> factory;


  // (var, version), version
  private final HashMap<VarVersionPair, FastSparseSetFactory.FastSparseSet<Integer>> phi;

  // version, protected ranges (catch, finally)
  private final Map<VarVersionPair, Integer> mapVersionFirstRange;

  // version, version
  private final Map<VarVersionPair, VarVersionPair> phantomppnodes; // ++ and --

  // node.id, version, version
  private final Map<String, HashMap<VarVersionPair, VarVersionPair>> phantomexitnodes; // finally exits

  // versions memory dependencies
  private final VarVersionsGraph ssuversions;

  // field access vars (exprent id, var id)
  private final Map<Integer, Integer> mapFieldVars;

  // field access counter
  private int fieldvarcounter = -1;

  // track assignments for finding effectively final vars (left var, right var)
  private final HashMap<VarVersionPair, VarVersionPair> varAssignmentMap;



  private RootStatement root;
  private StructMethod mt;
  private DirectGraph dgraph;

  public SSAProcessor(
    boolean incrementOnUsage,
    boolean simplePhi,
    boolean trackFieldVars,
    boolean trackPhantomPPNodes,
    boolean trackPhantomExitNodes,
    boolean trackSsuVersions,
    boolean doLiveVariableAnalysisRound,
    boolean trackDirectAssignments,
    boolean blockFieldPropagation,
    boolean ssau) {
    this.incrementOnUsage = incrementOnUsage;
    this.simplePhi = simplePhi;
    this.trackFieldVars = trackFieldVars;
    this.trackPhantomPPNodes = trackPhantomPPNodes;
    this.trackPhantomExitNodes = trackPhantomExitNodes;
    this.trackSsuVersions = trackSsuVersions;
    this.doLiveVariableAnalysisRound = doLiveVariableAnalysisRound;
    this.trackDirectAssignments = trackDirectAssignments;
    this.blockFieldPropagation = blockFieldPropagation;
    this.ssau = ssau;


    this.phi = simplePhi ? new HashMap<>() : null;
    this.mapVersionFirstRange = ssau ? new HashMap<>() : null;
    this.phantomppnodes = trackPhantomPPNodes ? new HashMap<>() : null;
    this.phantomexitnodes = trackPhantomExitNodes ? new HashMap<>() : null;
    this.ssuversions = trackSsuVersions ? new VarVersionsGraph() : null;
    this.mapFieldVars = trackFieldVars ? new HashMap<>() : null;
    this.varAssignmentMap = trackDirectAssignments ? new HashMap<>() : null;

    // doLiveVariableAnalysisRound -> trackSsuVersions
    assert !this.doLiveVariableAnalysisRound || this.trackSsuVersions;
    // incrementOnUsage -> trackSsuVersions
    assert !this.incrementOnUsage || this.trackSsuVersions;
  }

  public void splitVariables(RootStatement root, StructMethod mt) {
    this.root = root;
    this.mt = mt;

    FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
    DirectGraph dgraph = flatthelper.buildDirectGraph(root);
    this.dgraph = dgraph;

    DotExporter.toDotFile(dgraph, mt, "ssaSplitVariables");

    List<Integer> setInit = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      setInit.add(i);
    }
    this.factory = new FastSparseSetFactory<>(setInit);

    this.extraVarVersions.put(dgraph.first.id, this.createFirstMap());

    this.setCatchMaps(root, dgraph, flatthelper);

    int iteration = 1;
    HashSet<String> updated = new HashSet<>();
    do {
      // System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
      this.ssaStatements(dgraph, updated, false, mt, iteration++);
      // System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
    }
    while (!updated.isEmpty());

    if(this.doLiveVariableAnalysisRound) {
      this.ssaStatements(dgraph, updated, true, mt, iteration);

      this.ssuversions.initDominators();
    }
  }

  private void ssaStatements(DirectGraph dgraph, HashSet<String> updated, boolean calcLiveVars, StructMethod mt, int iteration) {

    DotExporter.toDotFile(dgraph, mt, "ssaStatements_" + iteration, this.outVarVersions);

    for (DirectNode node : dgraph.nodes) {

      updated.remove(node.id);
      this.mergeInVarMaps(node, dgraph);

      SFormsFastMapDirect varmap = this.inVarVersions.get(node.id);
      VarMapHolder varmaps = VarMapHolder.ofNormal(varmap);

      if (node.exprents != null) {
        for (Exprent expr : node.exprents) {
          varmaps.toNormal(); // make sure we are in normal form
          this.processExprent(node, expr, varmaps, node.statement, calcLiveVars);
        }
      }

      if(this.blockFieldPropagation) {
        // quick solution: 'dummy' field variables should not cross basic block borders (otherwise problems e.g. with finally loops - usage without assignment in a loop)
        // For the full solution consider adding a dummy assignment at the entry point of the method
        boolean allow_field_propagation = node.succs.isEmpty() || (node.succs.size() == 1 && node.succs.get(0).preds.size() == 1);

        if (!allow_field_propagation) {
          varmaps.getIfTrue().removeAllFields();
          varmaps.getIfFalse().removeAllFields();
        }
      }

      if (this.hasUpdated(node, varmaps)) {
        this.outVarVersions.put(node.id, varmaps.getIfTrue());
        if (dgraph.mapNegIfBranch.containsKey(node.id)) {
          this.outNegVarVersions.put(node.id, varmaps.getIfFalse());
        }

        // Don't update the node if it wasn't discovered normally, as that can lead to infinite recursion due to bad ordering!
        if (!dgraph.extraNodes.contains(node)) {
          for (DirectNode nd : node.succs) {
            updated.add(nd.id);
          }
        }
      }
    }
  }

  private void processExprent(DirectNode node, Exprent expr, VarMapHolder varmaps, Statement stat, boolean calcLiveVars) {

    if (expr == null) {
      return;
    }

    assert varmaps.isNormal();

    switch (expr.type) {
      case Exprent.EXPRENT_IF: {
        IfExprent ifexpr = (IfExprent) expr;
        this.processExprent(node, ifexpr.getCondition(), varmaps, stat, calcLiveVars);
        return;
      }
      case Exprent.EXPRENT_ASSIGNMENT: {
        AssignmentExprent assexpr = (AssignmentExprent) expr;
        // Kropp : pretty sure ssa(u) gives invalid results if this asserts fails
        assert assexpr.getCondType() == AssignmentExprent.CONDITION_NONE;
        if (assexpr.getCondType() == AssignmentExprent.CONDITION_NONE) {
          Exprent dest = assexpr.getLeft();
          switch (dest.type) {
            case Exprent.EXPRENT_VAR: {
              final VarExprent destVar = (VarExprent) dest;

              this.processExprent(node, assexpr.getRight(), varmaps, stat, calcLiveVars);
              this.updateVarExprent(destVar, stat, varmaps.getNormal(), calcLiveVars);
              if(this.trackDirectAssignments) {

                switch (assexpr.getRight().type) {
                  case Exprent.EXPRENT_VAR: {
                    VarVersionPair rightpaar = ((VarExprent) assexpr.getRight()).getVarVersionPair();
                    this.varAssignmentMap.put(destVar.getVarVersionPair(), rightpaar);
                    break;
                  }
                  case Exprent.EXPRENT_FIELD: {
                    int index = this.mapFieldVars.get(assexpr.getRight().id);
                    VarVersionPair rightpaar = new VarVersionPair(index, 0);
                    this.varAssignmentMap.put(destVar.getVarVersionPair(), rightpaar);
                    break;
                  }
                }
              }

              return;
            }
            case Exprent.EXPRENT_FIELD: {
              this.processExprent(node, assexpr.getLeft(), varmaps, stat, calcLiveVars);
              varmaps.toNormal();
              this.processExprent(node, assexpr.getRight(), varmaps, stat, calcLiveVars);
              varmaps.toNormal();
              if (this.trackFieldVars){
                varmaps.getNormal().removeAllFields();
              }
              return;
            }
            default: {
              this.processExprent(node, assexpr.getLeft(), varmaps, stat, calcLiveVars);
              varmaps.toNormal();
              this.processExprent(node, assexpr.getRight(), varmaps, stat, calcLiveVars);
              varmaps.toNormal();
              return;
            }
          }
        }

        break;
      }
      case Exprent.EXPRENT_FUNCTION: {
        FunctionExprent func = (FunctionExprent) expr;
        switch (func.getFuncType()) {
          case FunctionExprent.FUNCTION_IIF: {
            // a ? b : c
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder bVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());
            this.processExprent(node, func.getLstOperands().get(1), bVarMaps, stat, calcLiveVars);

            VarMapHolder cVarMaps = VarMapHolder.ofNormal(varmaps.getIfFalse());
            this.processExprent(node, func.getLstOperands().get(2), cVarMaps, stat, calcLiveVars);

            if (bVarMaps.isNormal() && cVarMaps.isNormal()) {
              varmaps.setNormal(mergeMaps(bVarMaps.getNormal(), cVarMaps.getNormal()));
            } else {
              bVarMaps.makeFullyMutable();
              bVarMaps.mergeIfTrue(cVarMaps.getIfTrue());
              bVarMaps.mergeIfFalse(cVarMaps.getIfFalse());

              varmaps.set(bVarMaps);
            }

            return;
          }
          case FunctionExprent.FUNCTION_CADD: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps, stat, calcLiveVars);

            // true map
            varmaps.setIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.mergeIfFalse(rightHandSideVarMaps.getIfFalse());

            return;
          }
          case FunctionExprent.FUNCTION_COR: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps, stat, calcLiveVars);

            // true map
            varmaps.mergeIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.setIfFalse(rightHandSideVarMaps.getIfFalse());

            return;
          }
          case FunctionExprent.FUNCTION_BOOL_NOT: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);
            varmaps.swap();

            return;
          }
          case FunctionExprent.FUNCTION_INSTANCEOF: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);
            varmaps.toNormal();

            if (func.getLstOperands().size() == 3) {
              // pattern matching
              varmaps.makeFullyMutable();

              this.updateVarExprent(
                (VarExprent) func.getLstOperands().get(2),
                stat,
                varmaps.getIfTrue(),
                calcLiveVars);
            }

            return;
          }
          case FunctionExprent.FUNCTION_IMM:
          case FunctionExprent.FUNCTION_MMI:
          case FunctionExprent.FUNCTION_IPP:
          case FunctionExprent.FUNCTION_PPI: {
            // process the var/field/array access
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);
            SFormsFastMapDirect varmap = varmaps.toNormal(); // should be normal already I think

            switch (func.getLstOperands().get(0).type) {
              case Exprent.EXPRENT_VAR: {
                // TODO: why doesn't ssa need to process these?
                if (!this.trackPhantomPPNodes){
                  return;
                }

                VarExprent var = (VarExprent) func.getLstOperands().get(0);

                int varindex = var.getIndex();
                VarVersionPair varpaar = new VarVersionPair(varindex, var.getVersion());

                // ssu graph
                VarVersionPair phantomver = this.phantomppnodes.get(varpaar);
                if (phantomver == null) {
                  // get next version
                  int nextver = this.getNextFreeVersion(varindex, null);
                  phantomver = new VarVersionPair(varindex, nextver);
                  //ssuversions.createOrGetNode(phantomver);
                  this.ssuversions.createNode(phantomver);

                  VarVersionNode vernode = this.ssuversions.nodes.getWithKey(varpaar);

                  FastSparseSetFactory.FastSparseSet<Integer> vers = this.factory.spawnEmptySet();
                  if (vernode.preds.size() == 1) {
                    vers.add(vernode.preds.iterator().next().source.version);
                  } else {
                    for (VarVersionEdge edge : vernode.preds) {
                      vers.add(edge.source.preds.iterator().next().source.version);
                    }
                  }
                  vers.add(nextver);
                  this.createOrUpdatePhiNode(varpaar, vers, stat);
                  this.phantomppnodes.put(varpaar, phantomver);
                }
                if (calcLiveVars) {
                  this.varMapToGraph(varpaar, varmap);
                }
                this.setCurrentVar(varmap, varindex, var.getVersion());
                return;
              }
              case Exprent.EXPRENT_FIELD: {
                if (this.trackFieldVars) {
                  varmap.removeAllFields();
                }
                return;
              }
              default:
                return;
            }
          }
        }
        break;
      }
      case Exprent.EXPRENT_FIELD: {
        if (this.trackFieldVars) {
          FieldExprent field = (FieldExprent) expr;
          this.processExprent(node, field.getInstance(), varmaps, stat, calcLiveVars);

          int index;
          if (this.mapFieldVars.containsKey(expr.id)) {
            index = this.mapFieldVars.get(expr.id);
          } else {
            index = this.fieldvarcounter--;
            this.mapFieldVars.put(expr.id, index);

            // ssu graph
            if (this.trackSsuVersions) {
              this.ssuversions.createNode(new VarVersionPair(index, 1));
            }
          }

          this.setCurrentVar(varmaps.getNormal(), index, 1);
        }
        return;
      }
      case Exprent.EXPRENT_VAR: {
        VarExprent vardest = (VarExprent) expr;
        final SFormsFastMapDirect varmap = varmaps.getNormal();

        int varindex = vardest.getIndex();
        int current_vers = vardest.getVersion();

        FastSparseSetFactory.FastSparseSet<Integer> vers = varmap.get(varindex);

        int cardinality = vers != null ? vers.getCardinality() : 0;
        switch (cardinality) {
          case 0: { // size == 0 (var has no discovered assignments yet)
            this.updateVarExprent(vardest, stat, varmap, calcLiveVars);
            break;
          }
          case 1: { // size == 1 (var has only one discovered assignment)
            if (!this.incrementOnUsage) {
              // simply increment
              int it = vers.iterator().next();
              vardest.setVersion(it);
            } else {
              if (current_vers == 0) {
                // split last version
                int usever = this.getNextFreeVersion(varindex, stat);

                // set version
                vardest.setVersion(usever);
                this.setCurrentVar(varmap, varindex, usever);

                // ssu graph
                int lastver = vers.iterator().next();
                VarVersionNode prenode = this.ssuversions.nodes.getWithKey(new VarVersionPair(varindex, lastver));
                VarVersionNode usenode = this.ssuversions.createNode(new VarVersionPair(varindex, usever));
                VarVersionEdge edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, prenode, usenode);
                prenode.addSuccessor(edge);
                usenode.addPredecessor(edge);
              } else {
                if (calcLiveVars) {
                  this.varMapToGraph(new VarVersionPair(varindex, current_vers), varmap);
                }

                this.setCurrentVar(varmap, varindex, current_vers);
              }
            }
            break;
          }
          case 2:  // size > 1 (var has more than one assignment)
            if (!this.incrementOnUsage) {

              // We need to know if this is already a phi node or not.
              assert this.simplePhi;

              VarVersionPair varVersion = new VarVersionPair(varindex, current_vers);
              if (current_vers != 0 && this.phi.containsKey(varVersion)) {
                this.setCurrentVar(varmap, varindex, current_vers);
                // keep phi node up to date of all inputs
                this.phi.get(varVersion).union(vers);
              } else {
                // increase version
                int nextver = this.getNextFreeVersion(varindex, stat);
                // set version
                vardest.setVersion(nextver);

                this.setCurrentVar(varmap, varindex, nextver);
                // create new phi node
                this.phi.put(new VarVersionPair(varindex, nextver), vers);
              }
            } else {
              if (current_vers != 0) {
                if (calcLiveVars) {
                  this.varMapToGraph(new VarVersionPair(varindex, current_vers), varmap);
                }
                this.setCurrentVar(varmap, varindex, current_vers);
              } else {
                // split version
                int usever = this.getNextFreeVersion(varindex, stat);
                // set version
                vardest.setVersion(usever);

                // ssu node
                this.ssuversions.createNode(new VarVersionPair(varindex, usever));

                this.setCurrentVar(varmap, varindex, usever);

                current_vers = usever;
              }

              this.createOrUpdatePhiNode(new VarVersionPair(varindex, current_vers), vers, stat);
            }
            break;
        }
        return;
      }
    }

    // Foreach init node- mark as assignment!
    if (node.type == DirectNode.NodeType.FOREACH_VARDEF && node.exprents.get(0).type == Exprent.EXPRENT_VAR) {
      this.updateVarExprent((VarExprent) node.exprents.get(0), stat, varmaps.getNormal(), calcLiveVars);
      return;
    }

    for (Exprent ex : expr.getAllExprents()) {
      this.processExprent(node, ex, varmaps, stat, calcLiveVars);
      varmaps.toNormal();
    }

    if (this.trackFieldVars && makesFieldsDirty(expr)) {
      varmaps.getNormal().removeAllFields();
    }
  }


  private static boolean makesFieldsDirty(Exprent expr) {
    switch (expr.type) {
      case Exprent.EXPRENT_INVOCATION:
        return true;
      // already handled
//      case Exprent.EXPRENT_FUNCTION: {
//        FunctionExprent fexpr = (FunctionExprent) expr;
//        if (fexpr.getFuncType() >= FunctionExprent.FUNCTION_IMM && fexpr.getFuncType() <= FunctionExprent.FUNCTION_PPI) {
//          if (fexpr.getLstOperands().get(0).type == Exprent.EXPRENT_FIELD) {
//            return true;
//          }
//        }
//        break;
//      }
      // already handled
//      case Exprent.EXPRENT_ASSIGNMENT:
//        if (((AssignmentExprent) expr).getLeft().type == Exprent.EXPRENT_FIELD) {
//          return true;
//        }
//        break;
      case Exprent.EXPRENT_NEW:
        if (((NewExprent) expr).getNewType().type == CodeConstants.TYPE_OBJECT) {
          return true;
        }
        break;
    }
    return false;
  }

  private void updateVarExprent(VarExprent varassign, Statement stat, SFormsFastMapDirect varmap, boolean calcLiveVars) {
    int varindex = varassign.getIndex();

    if (varassign.getVersion() == 0) {
      // get next version
      int nextver = this.getNextFreeVersion(varindex, stat);

      // set version
      varassign.setVersion(nextver);

      if (this.trackSsuVersions) {
        // ssu graph
        this.ssuversions.createNode(new VarVersionPair(varindex, nextver), varassign.getLVT());
      }

      this.setCurrentVar(varmap, varindex, nextver);
    } else {
      if (calcLiveVars) {
        this.varMapToGraph(new VarVersionPair(varindex, varassign.getVersion()), varmap);
      }

      this.setCurrentVar(varmap, varindex, varassign.getVersion());
    }
  }

  @Deprecated
  private int getNextFreeVersion(int var) {
    assert !this.ssau;
    return this.getNextFreeVersion(var, null);
  }

  private int getNextFreeVersion(int var, Statement stat) {
    final int nextver = this.lastversion.compute(var, (k, v) -> v == null ? 1 : v + 1);

    // save the first protected range, containing current statement
    if (this.ssau && stat != null) { // null iff phantom version
      Integer firstRangeId = getFirstProtectedRange(stat);

      if (firstRangeId != null) {
        this.mapVersionFirstRange.put(new VarVersionPair(var, nextver), firstRangeId);
      }
    }

    return nextver;
  }

  private void mergeInVarMaps(DirectNode node, DirectGraph dgraph) {

    SFormsFastMapDirect mapNew = new SFormsFastMapDirect();

    for (DirectNode pred : node.preds) {
      SFormsFastMapDirect mapOut = this.getFilteredOutMap(node.id, pred.id, dgraph, node.id);
      if (mapNew.isEmpty()) {
        mapNew = mapOut.getCopy();
      } else {
        mergeMaps(mapNew, mapOut);
      }
    }

    if (this.extraVarVersions.containsKey(node.id)) {
      SFormsFastMapDirect mapExtra = this.extraVarVersions.get(node.id);
      if (mapNew.isEmpty()) {
        mapNew = mapExtra.getCopy();
      } else {
        mergeMaps(mapNew, mapExtra);
      }
    }

    this.inVarVersions.put(node.id, mapNew);
  }

  private SFormsFastMapDirect getFilteredOutMap(String nodeid, String predid, DirectGraph dgraph, String destid) {

    SFormsFastMapDirect mapNew = new SFormsFastMapDirect();

    if (nodeid.equals(dgraph.mapNegIfBranch.get(predid))) {
      if (this.outNegVarVersions.containsKey(predid)) {
        mapNew = this.outNegVarVersions.get(predid).getCopy();
      }
    } else if (this.outVarVersions.containsKey(predid)) {
      mapNew = this.outVarVersions.get(predid).getCopy();
    }

    boolean isFinallyExit = dgraph.mapShortRangeFinallyPaths.containsKey(predid);

    if (isFinallyExit && !mapNew.isEmpty()) {

      SFormsFastMapDirect mapNewTemp = mapNew.getCopy();

      SFormsFastMapDirect mapTrueSource = new SFormsFastMapDirect();

      String exceptionDest = dgraph.mapFinallyMonitorExceptionPathExits.get(predid);
      boolean isExceptionMonitorExit = (exceptionDest != null && !nodeid.equals(exceptionDest));

      HashSet<String> setLongPathWrapper = new HashSet<>();
      for (FlattenStatementsHelper.FinallyPathWrapper finwraplong : dgraph.mapLongRangeFinallyPaths.get(predid)) {
        setLongPathWrapper.add(finwraplong.destination + "##" + finwraplong.source);
      }

      for (FlattenStatementsHelper.FinallyPathWrapper finwrap : dgraph.mapShortRangeFinallyPaths.get(predid)) {
        SFormsFastMapDirect map;

        boolean recFinally = dgraph.mapShortRangeFinallyPaths.containsKey(finwrap.source);

        if (recFinally) {
          // recursion
          map = this.getFilteredOutMap(finwrap.entry, finwrap.source, dgraph, destid);
        } else {
          if (finwrap.entry.equals(dgraph.mapNegIfBranch.get(finwrap.source))) {
            map = this.outNegVarVersions.get(finwrap.source);
          } else {
            map = this.outVarVersions.get(finwrap.source);
          }
        }

        // false path?
        boolean isFalsePath;

        if (recFinally) {
          isFalsePath = !finwrap.destination.equals(nodeid);
        } else {
          isFalsePath = !setLongPathWrapper.contains(destid + "##" + finwrap.source);
        }

        if (isFalsePath) {
          mapNewTemp.complement(map);
        } else {
          if (mapTrueSource.isEmpty()) {
            if (map != null) {
              mapTrueSource = map.getCopy();
            }
          } else {
            mergeMaps(mapTrueSource, map);
          }
        }
      }

      if (isExceptionMonitorExit) {

        mapNew = mapTrueSource;
      } else {

        mapNewTemp.union(mapTrueSource);

        SFormsFastMapDirect oldInMap = this.inVarVersions.get(nodeid);
        if (oldInMap != null) {
          mapNewTemp.union(oldInMap);
        }

        mapNew.intersection(mapNewTemp);
      }
    }

    return mapNew;
  }


  private static Integer getFirstProtectedRange(Statement stat) {
    while (true) {
      Statement parent = stat.getParent();

      if (parent == null) {
        break;
      }

      if (parent.type == Statement.TYPE_CATCHALL ||
          parent.type == Statement.TYPE_TRYCATCH) {
        if (parent.getFirst() == stat) {
          return parent.id;
        }
      } else if (parent.type == Statement.TYPE_SYNCRONIZED) {
        if (((SynchronizedStatement) parent).getBody() == stat) {
          return parent.id;
        }
      }

      stat = parent;
    }

    return null;
  }

  private void setCatchMaps(Statement stat, DirectGraph dgraph, FlattenStatementsHelper flatthelper) {

    SFormsFastMapDirect map;

    switch (stat.type) {
      case Statement.TYPE_CATCHALL:
      case Statement.TYPE_TRYCATCH:

        List<VarExprent> lstVars;
        if (stat.type == Statement.TYPE_CATCHALL) {
          lstVars = ((CatchAllStatement) stat).getVars();
        } else {
          lstVars = ((CatchStatement) stat).getVars();
        }

        for (int i = 1; i < stat.getStats().size(); i++) {
          int varindex = lstVars.get(i - 1).getIndex();
          int version = this.getNextFreeVersion(varindex , stat); // == 1

          map = new SFormsFastMapDirect();
          this.setCurrentVar(map, varindex, version);

          this.extraVarVersions.put(dgraph.nodes.getWithKey(flatthelper.getMapDestinationNodes().get(stat.getStats().get(i).id)[0]).id, map);
          if(this.trackSsuVersions) {
            this.ssuversions.createNode(new VarVersionPair(varindex, version));
          }
        }
    }

    for (Statement st : stat.getStats()) {
      this.setCatchMaps(st, dgraph, flatthelper);
    }
  }

  private SFormsFastMapDirect createFirstMap() {
    boolean thisvar = !this.mt.hasModifier(CodeConstants.ACC_STATIC);

    MethodDescriptor md = MethodDescriptor.parseDescriptor(this.mt.getDescriptor());

    int paramcount = md.params.length + (thisvar ? 1 : 0);

    int varindex = 0;
    SFormsFastMapDirect map = new SFormsFastMapDirect();
    for (int i = 0; i < paramcount; i++) {
      int version = this.getNextFreeVersion(varindex, this.root); // == 1

      FastSparseSetFactory.FastSparseSet<Integer> set = this.factory.spawnEmptySet();
      set.add(version);
      map.put(varindex, set);

      if (this.trackSsuVersions) {
        this.ssuversions.createNode(new VarVersionPair(varindex, version));
      }

      if (thisvar) {
        if (i == 0) {
          varindex++;
        } else {
          varindex += md.params[i - 1].stackSize;
        }
      } else {
        varindex += md.params[i].stackSize;
      }
    }

    return map;
  }

  public HashMap<VarVersionPair, FastSparseSetFactory.FastSparseSet<Integer>> getPhi() {
    return this.phi;
  }


  private void createOrUpdatePhiNode(VarVersionPair phivar, FastSparseSetFactory.FastSparseSet<Integer> vers, Statement stat) {

//    FastSparseSet<Integer> versCopy = vers.getCopy();
    Set<Integer> removed = new HashSet<>();
//    HashSet<Integer> phiVers = new HashSet<>();

    // take into account the corresponding mm/pp node if existing
    int ppvers = this.phantomppnodes.containsKey(phivar) ? this.phantomppnodes.get(phivar).version : -1;

    // ssu graph
    VarVersionNode phinode = this.ssuversions.nodes.getWithKey(phivar);
    List<VarVersionEdge> lstPreds = new ArrayList<>(phinode.preds);
    if (lstPreds.size() == 1) {
      // not yet a phi node
      VarVersionEdge edge = lstPreds.get(0);
      edge.source.removeSuccessor(edge);
      phinode.removePredecessor(edge);
    } else {
      for (VarVersionEdge edge : lstPreds) {
        int verssrc = edge.source.preds.iterator().next().source.version;
        if (!vers.contains(verssrc) && verssrc != ppvers) {
          edge.source.removeSuccessor(edge);
          phinode.removePredecessor(edge);
        } else {
//          versCopy.remove(verssrc);
          removed.add(verssrc);
//          phiVers.add(verssrc);
        }
      }
    }

    List<VarVersionNode> colnodes = new ArrayList<>();
    List<VarVersionPair> colpaars = new ArrayList<>();

//    for (int ver : versCopy) {
    for (int ver : vers) {
      if (removed.contains(ver)) {
        continue;
      }

      VarVersionNode prenode = this.ssuversions.nodes.getWithKey(new VarVersionPair(phivar.var, ver));

      int tempver = this.getNextFreeVersion(phivar.var, stat);

      VarVersionNode tempnode = new VarVersionNode(phivar.var, tempver);

      colnodes.add(tempnode);
      colpaars.add(new VarVersionPair(phivar.var, tempver));

      VarVersionEdge edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, prenode, tempnode);

      prenode.addSuccessor(edge);
      tempnode.addPredecessor(edge);


      edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, tempnode, phinode);
      tempnode.addSuccessor(edge);
      phinode.addPredecessor(edge);

//      phiVers.add(tempver);
    }

    this.ssuversions.addNodes(colnodes, colpaars);
  }


  private void varMapToGraph(VarVersionPair varpaar, SFormsFastMapDirect varmap) {
    assert this.trackSsuVersions;

    VBStyleCollection<VarVersionNode, VarVersionPair> nodes = this.ssuversions.nodes;

    VarVersionNode node = nodes.getWithKey(varpaar);

//    node.live = new SFormsFastMapDirect(varmap);
    node.live = varmap.getCopy();
  }


  static boolean mapsEqual(SFormsFastMapDirect map1, SFormsFastMapDirect map2) {

    if (map1 == null) {
      return map2 == null;
    } else if (map2 == null) {
      return false;
    }

    if (map1.size() != map2.size()) {
      return false;
    }

    for (Map.Entry<Integer, FastSparseSetFactory.FastSparseSet<Integer>> ent2 : map2.entryList()) {
      if (!InterpreterUtil.equalObjects(map1.get(ent2.getKey()), ent2.getValue())) {
        return false;
      }
    }

    return true;
  }

  void setCurrentVar(SFormsFastMapDirect varmap, int var, int vers) {
    FastSparseSetFactory.FastSparseSet<Integer> set = this.factory.spawnEmptySet();
    set.add(vers);
    varmap.put(var, set);
  }

  boolean hasUpdated(DirectNode node, VarMapHolder varmaps) {
    return !mapsEqual(varmaps.getIfTrue(), this.outVarVersions.get(node.id))
           || (this.outNegVarVersions.containsKey(node.id) && !mapsEqual(varmaps.getIfFalse(), this.outNegVarVersions.get(node.id)));
  }


  public VarVersionsGraph getSsuVersions() {
    return this.ssuversions;
  }

  public SFormsFastMapDirect getLiveVarVersionsMap(VarVersionPair varpaar) {
    assert this.trackSsuVersions;

    VarVersionNode node = this.ssuversions.nodes.getWithKey(varpaar);
    if (node != null) {
      return node.live == null ? new SFormsFastMapDirect() : node.live;
    }

    return null;
  }

  public Map<VarVersionPair, Integer> getMapVersionFirstRange() {
    assert this.ssau;
    return this.mapVersionFirstRange;
  }

  public Map<Integer, Integer> getMapFieldVars() {
    assert this.trackFieldVars;
    return this.mapFieldVars;
  }

  public Map<VarVersionPair, VarVersionPair> getVarAssignmentMap() {
    assert this.trackDirectAssignments;
    return this.varAssignmentMap;
  }
}