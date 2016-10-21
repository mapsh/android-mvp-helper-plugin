package com.mapsh.mvp.helper;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.containers.HashMap;
import com.mapsh.mvp.helper.util.PsiUtils;
import org.apache.http.util.TextUtils;

import java.io.File;
import java.util.Map;

class CodeGenerator {

    private enum UiType {
        UNDEFINE,//未指定
        ACTIVITY,//Activity
        FRAGMENT,//Fragment
    }

    private UiType uiType = UiType.UNDEFINE;

    private static StringBuilder mvpSuperClass(UiType uiType) {
        StringBuilder sb = new StringBuilder();
        switch (uiType) {
            case ACTIVITY:
                sb.append("MvpActivity");
                break;
            case FRAGMENT:
                sb.append("MvpFragment");
                break;
            case UNDEFINE:
            default:
                break;
        }

        return sb;
    }

    private static String addMvpSuperClass(UiType uiType, String v, String p) {
        return mvpSuperClass(uiType).append("<").append(v).append(",").append(p).append(">").toString();
    }

    private static final String CREATOR_NAME = "CREATOR";
   private static final String TYPE_ACTIVITY = "com.mapsh.sdk.mvp.MvpActivity";
    static final String TYPE_FRAGMENT = "com.mapsh.sdk.mvp.MvpFragment";

    //前缀
    private String mPrefix;
    private final Project mProject;
    //主类，
    private final PsiClass mClass;
    //主类所在的文件夹目录
    private final String mPath;
    //package
    private final String mPackageName;
    private final String mContractView;
    private final String mContractPresenter;


    CodeGenerator(PsiClass psiClass) {

        mClass = psiClass;
        mProject = mClass.getProject();
        mPath = mClass.getContainingFile().getParent() == null ? "" : mClass.getContainingFile().getParent().getVirtualFile().getPath();
        mPackageName = ((PsiJavaFile) mClass.getContainingFile()).getPackageName();

        String mClazzType;
        int pos;
        String clazzName = mClass.getName();
        if (clazzName != null && clazzName.contains("Activity")) {
            mClazzType = "Activity";
            pos = clazzName.indexOf(mClazzType);
            mPrefix = clazzName.substring(0, pos);
            uiType = UiType.ACTIVITY;
        }
        if (clazzName != null && clazzName.contains("Fragment")) {
            mClazzType = "Fragment";
            pos = clazzName.indexOf(mClazzType);
            mPrefix = clazzName.substring(0, pos);
            uiType = UiType.FRAGMENT;
        }

        mContractView = mPrefix + "Contract.View";
        mContractPresenter = mPrefix + "Contract.Presenter";
    }


    private boolean hasSuperMethod(String methodName) {

        if (methodName == null) return false;

        PsiMethod[] superclassMethods = mClass.getSuperClass() != null ? mClass.getAllMethods() : new PsiMethod[0];
        for (PsiMethod superclassMethod : superclassMethods) {
            if (superclassMethod.getBody() == null) continue;

            String name = superclassMethod.getName();
            if (name != null && name.equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    //格式化代码
    private void reformat(PsiClass psiClass) {
        CodeStyleManager.getInstance(psiClass.getProject()).reformat(psiClass);
    }


    private boolean checkFileIsExists() {
        File filePresenter = new File(mPath + "/" + mPrefix + "Presenter.java");
        File fileContract = new File(mPath + "/" + mPrefix + "Contract.java");
        return filePresenter.exists() || fileContract.exists();
    }

    /**
     * 生成 前缀+Presenter.java 文件
     */
    private void generatePresenterJava() {
        Map<String, String> map = new HashMap<>();
        map.put("VIEW", mContractView);
        map.put("PRESENTER", mContractPresenter);

        PsiClass clazz = JavaDirectoryService.getInstance().createClass(mClass.getContainingFile().getParent(), mPrefix + "Presenter", "MapshMvpPresenter", false, map);
    }

    /**
     * 生成 前缀+Contract.java 文件
     */
    private void generateContractJava() {
        PsiClass clazz = JavaDirectoryService.getInstance().createClass(mClass.getContainingFile().getParent(), mPrefix + "Contract", "MapshMvpContract");
    }

    /**
     *
     */
    void generate() {

        if (TextUtils.isEmpty(mPrefix)) {
            MessageDialogBuilder.yesNo("提示", "前缀不能为空，请参照MainActivity或者MainFragment这样的形式命名类的名称！").show();
            return;
        }

        if (checkFileIsExists()) {
            Messages.showMessageDialog(mPrefix + "Presenter.java、" + mPrefix + "Contract.java" + "文件已经存在，避免覆盖，退出代码生成。", "提示", Messages.getErrorIcon());
            return;
        }

        PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(mClass.getProject());

        //生成Contract.java文件
        generateContractJava();
        //生成Presenter.java文件
        generatePresenterJava();

        //mClass.getContainingFile().getContainingDirectory().;

        //添加 import 声明
        GlobalSearchScope searchScope = GlobalSearchScope.allScope(mClass.getProject());
        insertImport(elementFactory, PsiShortNamesCache.getInstance(mClass.getProject()).getClassesByName("android.support.annotation.NonNull", searchScope));
        insertImport(elementFactory, PsiShortNamesCache.getInstance(mClass.getProject()).getClassesByName(mvpSuperClass(uiType).toString(), searchScope));


        //继承Mvp基类
        insertSuperMvpClass(elementFactory);
        //实现Contract.View接口
        insertImplement(elementFactory);

        //删除 createPresenter 方法，并生成新的方法
        findAndRemoveMethod(mClass, "createPresenter");
        JavaCodeStyleManager manager = JavaCodeStyleManager.getInstance(mClass.getProject());
        String createPresenterString = generateCreatePresenterMethod();
        PsiMethod createPresenterMethod = elementFactory.createMethodFromText(createPresenterString, mClass);
        manager.shortenClassReferences(mClass.addBefore(createPresenterMethod, mClass.getLastChild()));

    }

    private void insertImport(PsiElementFactory elementFactory, PsiClass[] psiClasses) {
        for (PsiClass psiClass : psiClasses) {
            PsiImportStatement importStatement = elementFactory.createImportStatement(psiClass);
            PsiImportList psiImportList = ((PsiJavaFile) mClass.getContainingFile()).getImportList();

            //如果已经有了，就不添加了
            for (PsiImportStatement i : psiImportList.getImportStatements()) {
                if (i.getQualifiedName().equals(importStatement.getQualifiedName())) {
                    return;
                }
            }

            psiImportList.add(importStatement);
        }
    }

    /**
     * 插入 View 接口
     *
     * @param elementFactory .
     */
    private void insertSuperMvpClass(PsiElementFactory elementFactory) {

        if (hasMvpSuperclass(mClass.getSuperTypes())) return;

        final PsiClassType[] extendsListTypes = mClass.getExtendsListTypes();
        final String mvpSuperClass = addMvpSuperClass(uiType, mContractView, mContractPresenter);

        for (PsiClassType extendsType : extendsListTypes) {
            PsiClass resolved = extendsType.resolve();

            // 已经 extends Mvp 基类, 不需要再添加
            if (resolved != null && mvpSuperClass.equals(resolved.getQualifiedName())) {
                return;
            }
        }

        PsiJavaCodeReferenceElement referenceElement = elementFactory.createReferenceFromText(mvpSuperClass, mClass);
        PsiReferenceList extendsList = mClass.getExtendsList();

        if (extendsList != null) {
            extendsList.add(referenceElement);
        }
    }

    /**
     * 插入 View 接口
     *
     * @param elementFactory .
     */
    private void insertImplement(PsiElementFactory elementFactory) {

        final PsiClassType[] implementsListTypes = mClass.getImplementsListTypes();
        final String implementsType = mContractView;

        for (PsiClassType implementsListType : implementsListTypes) {
            PsiClass resolved = implementsListType.resolve();

            // 已经 implements Mvp View 接口, 不需要再添加
            if (resolved != null && (mPackageName + "." + implementsType).equals(resolved.getQualifiedName())) {
                return;
            }
        }

        PsiJavaCodeReferenceElement implementsReference = elementFactory.createReferenceFromText(implementsType, mClass);
        PsiReferenceList implementsList = mClass.getImplementsList();

        if (implementsList != null) {
            implementsList.add(implementsReference);
        }
    }

    private boolean hasMvpSuperclass(PsiClassType[] psiClassTypes) {
        for (PsiClassType classType : psiClassTypes) {
            if (uiType == UiType.ACTIVITY) {
                if (PsiUtils.isOfType(classType, TYPE_ACTIVITY)) {
                    return true;
                }
            } else if (uiType == UiType.FRAGMENT) {
                if (PsiUtils.isOfType(classType, TYPE_FRAGMENT)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void findAndRemoveMethod(PsiClass clazz, String methodName, String... arguments) {
        // Maybe there's an easier way to do this with mClass.findMethodBySignature(), but I'm not an expert on Psi*
        PsiMethod[] methods = clazz.findMethodsByName(methodName, false);

        for (PsiMethod method : methods) {
            PsiParameterList parameterList = method.getParameterList();

            if (parameterList.getParametersCount() == arguments.length) {
                boolean shouldDelete = true;

                PsiParameter[] parameters = parameterList.getParameters();

                for (int i = 0; i < arguments.length; i++) {
                    if (!parameters[i].getType().getCanonicalText().equals(arguments[i])) {
                        shouldDelete = false;
                    }
                }

                if (shouldDelete) {
                    method.delete();
                }
            }
        }
    }

    /**
     * 生成 createPresenter() 方法
     *
     * @return
     */
    private String generateCreatePresenterMethod() {
        StringBuilder sb = new StringBuilder("");
        sb.append("@NonNull ");
        sb.append("@Override ");
        sb.append("public ");
        sb.append(mPrefix + "Contract.Presenter");
        sb.append(" createPresenter(){");
        sb.append(" return new " + mPrefix + "Presenter();");
        sb.append("}");
        return sb.toString();
    }
}
