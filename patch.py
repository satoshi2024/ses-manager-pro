import os
import glob
import re

controllers_dir = r'c:\Users\pc\Documents\ses-manager-pro\src\main\java\com\ses\controller\api'
for file in glob.glob(os.path.join(controllers_dir, '*ApiController.java')):
    with open(file, 'r', encoding='utf-8') as f:
        content = f.read()

    # getById(id)
    content = re.sub(
        r'return ApiResult\.success\(([\w]+Service)\.getById\((id)\)\);',
        r'var entity = \1.getById(\2);\n        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");\n        return ApiResult.success(entity);',
        content
    )

    # updateById(entity)
    content = re.sub(
        r'return ApiResult\.success\(([\w]+Service)\.updateById\(([\w]+)\)\);',
        r'boolean success = \1.updateById(\2);\n        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");\n        return ApiResult.success(true);',
        content
    )

    # removeById(id)
    content = re.sub(
        r'return ApiResult\.success\(([\w]+Service)\.removeById\((id)\)\);',
        r'boolean success = \1.removeById(\2);\n        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");\n        return ApiResult.success(true);',
        content
    )

    with open(file, 'w', encoding='utf-8') as f:
        f.write(content)